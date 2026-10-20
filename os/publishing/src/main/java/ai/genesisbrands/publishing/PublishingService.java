package ai.genesisbrands.publishing;

import ai.genesisbrands.platform.channel.ChannelException;
import ai.genesisbrands.platform.channel.PublishChannel;
import ai.genesisbrands.platform.channel.PublishChannel.ChannelAccountRef;
import ai.genesisbrands.platform.channel.PublishChannel.ChannelGrant;
import ai.genesisbrands.platform.channel.PublishChannel.ChannelNetwork;
import ai.genesisbrands.platform.channel.PublishChannel.ChannelPost;
import ai.genesisbrands.platform.channel.PublishChannel.Media;
import ai.genesisbrands.platform.channel.PublishChannel.PostMetrics;
import ai.genesisbrands.platform.standing.SocialPostReader;
import ai.genesisbrands.service.BlobStorageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Connected accounts and publish jobs, for whichever {@link PublishChannel} beans the app
 * adopts. Every client operation takes the client id the caller resolved server-side and only
 * touches that client's own rows. Platform tokens are decrypted only to call the platform.
 */
@Service
public class PublishingService implements SocialPostReader {

    private static final Logger log = LoggerFactory.getLogger(PublishingService.class);

    static final Duration STATE_TTL = Duration.ofMinutes(15);
    static final Duration MEDIA_TTL = Duration.ofHours(2);
    static final Duration MAX_AHEAD = Duration.ofDays(366);
    static final int MAX_ATTEMPTS = 3;
    static final String MEDIA_ROOT = "publishing/";

    private final Map<String, PublishChannel> channels = new LinkedHashMap<>();
    private final ChannelAccountRepository accounts;
    private final PublishJobRepository jobs;
    private final TokenVault vault;
    private final BlobStorageService blobs;
    private final ObjectMapper objectMapper;
    private final String publicBaseUrl;

    public PublishingService(List<PublishChannel> channels, ChannelAccountRepository accounts, PublishJobRepository jobs,
                             TokenVault vault, BlobStorageService blobs, ObjectMapper objectMapper,
                             @Value("${genesis.publishing.public-base-url:}") String publicBaseUrl) {
        channels.forEach(c -> this.channels.put(c.providerId(), c));
        this.accounts = accounts;
        this.jobs = jobs;
        this.vault = vault;
        this.blobs = blobs;
        this.objectMapper = objectMapper;
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim().replaceAll("/+$", "");
    }

    // ---- views -------------------------------------------------------------------------

    public record ProviderView(String providerId, String displayName, boolean configured, List<ChannelNetwork> networks) {}

    public record AccountView(String id, String provider, String network, String networkLabel, String displayName,
                              String status, Instant expiresAt, String lastError, Instant connectedAt) {}

    public record JobView(String id, String accountId, String accountName, String provider, String network,
                          String networkLabel, String sourceRef, Instant scheduledAt, String status, int attempts,
                          String url, String error, Instant createdAt, Instant finishedAt,
                          PostMetrics metrics, Instant metricsAt) {}

    /** {@code ready} is false until the operator sets the token key and public URL; nothing can connect until then. */
    public record ClientChannels(boolean ready, List<ProviderView> providers, List<AccountView> accounts) {}

    /** Publishing works only with both the token key (encryption, signing) and a public URL (OAuth redirects, media). */
    public boolean ready() {
        return vault.ready() && !publicBaseUrl.isEmpty();
    }

    public List<ProviderView> providers() {
        return channels.values().stream()
            .map(c -> new ProviderView(c.providerId(), c.displayName(), c.configured(), c.networks())).toList();
    }

    public ClientChannels forClient(String clientUserId) {
        return new ClientChannels(ready(), providers(),
            accounts.findByClientUserIdOrderByConnectedAtAsc(clientUserId).stream().map(this::view).toList());
    }

    /** The client's connected accounts that can take a post on {@code network}. */
    public List<AccountView> accountsFor(String clientUserId, String network) {
        return accounts.findByClientUserIdOrderByConnectedAtAsc(clientUserId).stream()
            .filter(a -> a.getNetwork().equals(network) && ChannelAccount.CONNECTED.equals(a.getStatus()))
            .map(this::view).toList();
    }

    // ---- connect -----------------------------------------------------------------------

    /** The platform's consent page for this client. {@code returnTo} is where the browser lands afterwards. */
    public String connectUrl(String clientUserId, String providerId, String returnTo) {
        PublishChannel channel = requireChannel(providerId);
        if (!ready() || !channel.configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, channel.displayName() + " is not set up yet.");
        }
        String state = vault.sign(String.join("\n", clientUserId, providerId, safeReturn(returnTo)), Instant.now().plus(STATE_TTL));
        return channel.authorizationUrl(state, redirectUri(providerId));
    }

    public record ConnectOutcome(String returnTo, List<AccountView> connected) {}

    /**
     * Finishes the platform's redirect. The state must be one this server signed for the same
     * signed-in client, so a link cannot attach someone else's accounts to this client.
     */
    public ConnectOutcome completeConnect(String providerId, String code, String state, String sessionClientId) {
        String[] parts = vault.verify(state, Instant.now()).map(s -> s.split("\n", 3)).orElse(null);
        if (parts == null || parts.length != 3 || !parts[1].equals(providerId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The connection link expired. Start again.");
        }
        String clientUserId = parts[0];
        if (!clientUserId.equals(sessionClientId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sign in with the account that started the connection.");
        }
        PublishChannel channel = requireChannel(providerId);
        List<ChannelGrant> grants;
        try {
            grants = channel.completeConnect(code, redirectUri(providerId));
        } catch (ChannelException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
        Instant now = Instant.now();
        List<AccountView> out = new ArrayList<>();
        for (ChannelGrant g : grants) {
            ChannelAccount a = accounts.findByClientUserIdAndProviderAndNetworkAndExternalId(clientUserId, providerId, g.network(), g.externalId())
                .orElseGet(() -> {
                    ChannelAccount fresh = new ChannelAccount();
                    fresh.setId(UUID.randomUUID().toString());
                    fresh.setClientUserId(clientUserId);
                    fresh.setProvider(providerId);
                    fresh.setNetwork(g.network());
                    fresh.setExternalId(g.externalId());
                    fresh.setConnectedAt(now);
                    return fresh;
                });
            a.setDisplayName(g.displayName());
            a.setAccessTokenEnc(vault.encrypt(g.accessToken()));
            a.setRefreshTokenEnc(vault.encrypt(g.refreshToken()));
            a.setExpiresAt(g.expiresAt());
            a.setStatus(ChannelAccount.CONNECTED);
            a.setLastError(null);
            a.setUpdatedAt(now);
            out.add(view(accounts.save(a)));
        }
        return new ConnectOutcome(parts[2], out);
    }

    /** The return path from a state, for sending the browser back even when the connection failed. */
    public String returnToOf(String state) {
        return vault.verify(state, Instant.now()).map(s -> s.split("\n", 3)).filter(p -> p.length == 3).map(p -> p[2]).orElse("/");
    }

    /** Removes the account and cancels its queued posts. Revoking the app on the platform stays with the client. */
    public void disconnect(String clientUserId, String accountId) {
        ChannelAccount a = accounts.findByIdAndClientUserId(accountId, clientUserId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such account"));
        for (PublishJob j : jobs.findByAccountIdAndStatus(a.getId(), PublishJob.QUEUED)) {
            j.setStatus(PublishJob.CANCELLED);
            j.setError("The account was disconnected.");
            j.setFinishedAt(Instant.now());
            jobs.save(j);
        }
        accounts.delete(a);
    }

    // ---- schedule ----------------------------------------------------------------------

    /**
     * Queues one post for one of the client's accounts. {@code at} null or in the past means
     * as soon as the scheduler next runs. Images are stored as JPEG in the job's own folder.
     */
    public JobView schedule(String clientUserId, String sourceRef, String accountId, String text, List<byte[]> images, Instant at) {
        if (!ready()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Publishing is not set up yet.");
        ChannelAccount account = accounts.findByIdAndClientUserId(accountId, clientUserId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such account"));
        if (!ChannelAccount.CONNECTED.equals(account.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, account.getDisplayName() + " needs to be connected again.");
        }
        ChannelNetwork network = network(account);
        String body = text == null ? "" : text.strip();
        List<byte[]> media = images == null ? List.of() : images.subList(0, Math.min(images.size(), network.maxImages()));
        if (body.length() > network.maxChars()) {
            throw badRequest(network.label() + " allows " + network.maxChars() + " characters; this post has " + body.length() + ".");
        }
        if (network.requiresImage() && media.isEmpty()) throw badRequest(network.label() + " posts need at least one image.");
        if (body.isEmpty() && media.isEmpty()) throw badRequest("The post is empty.");
        Instant now = Instant.now();
        Instant when = at == null || at.isBefore(now) ? now : at;
        if (when.isAfter(now.plus(MAX_AHEAD))) throw badRequest("Posts can be scheduled up to a year ahead.");
        if (sourceRef != null && jobs.existsByClientUserIdAndSourceRefAndAccountIdAndStatusIn(clientUserId, sourceRef, accountId,
                List.of(PublishJob.QUEUED, PublishJob.RUNNING, PublishJob.PUBLISHED))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This post is already scheduled or published to " + account.getDisplayName() + ".");
        }

        PublishJob job = new PublishJob();
        job.setId(UUID.randomUUID().toString());
        job.setClientUserId(clientUserId);
        job.setAccountId(accountId);
        job.setProvider(account.getProvider());
        job.setNetwork(account.getNetwork());
        job.setSourceRef(sourceRef);
        job.setText(body);
        job.setScheduledAt(when);
        job.setCreatedAt(now);
        List<String> paths = new ArrayList<>();
        try {
            for (int n = 0; n < media.size(); n++) {
                String path = MEDIA_ROOT + clientUserId + "/" + job.getId() + "/" + (n + 1) + ".jpg";
                blobs.upload(path, JpegImages.toJpeg(media.get(n)));
                paths.add(path);
            }
            job.setMediaJson(objectMapper.writeValueAsString(paths));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not prepare the images for publishing.");
        }
        return view(jobs.save(job), Map.of(account.getId(), account));
    }

    public List<JobView> jobs(String clientUserId, String sourceRef) {
        List<PublishJob> list = jobs.findByClientUserIdAndSourceRefOrderByCreatedAtAsc(clientUserId, sourceRef);
        Map<String, ChannelAccount> byId = accountsById(list);
        return list.stream().map(j -> view(j, byId)).toList();
    }

    public List<JobView> recentJobs(String clientUserId, int limit) {
        List<PublishJob> list = jobs.findByClientUserIdOrderByScheduledAtDesc(clientUserId, PageRequest.of(0, Math.max(1, Math.min(100, limit))));
        Map<String, ChannelAccount> byId = accountsById(list);
        return list.stream().map(j -> view(j, byId)).toList();
    }

    public JobView cancel(String clientUserId, String jobId) {
        PublishJob job = jobs.findByIdAndClientUserId(jobId, clientUserId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such post"));
        if (!PublishJob.QUEUED.equals(job.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a post that is still waiting can be cancelled.");
        }
        job.setStatus(PublishJob.CANCELLED);
        job.setFinishedAt(Instant.now());
        return view(jobs.save(job), accountsById(List.of(job)));
    }

    // ---- run ---------------------------------------------------------------------------

    /**
     * The client's own connected accounts, read through their channels for agents that review
     * what was posted. An account whose token was revoked is marked for reconnecting, as when
     * publishing; any other failure only skips that account.
     */
    @Override
    public Feed recentPosts(String clientUserId, int perAccount) {
        if (!ready()) return Feed.empty();
        List<SocialPost> posts = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        for (ChannelAccount a : accounts.findByClientUserIdOrderByConnectedAtAsc(clientUserId)) {
            PublishChannel channel = channels.get(a.getProvider());
            if (channel == null || !ChannelAccount.CONNECTED.equals(a.getStatus())) continue;
            String label = networkLabel(a.getProvider(), a.getNetwork());
            try {
                ChannelAccountRef ref = new ChannelAccountRef(a.getNetwork(), a.getExternalId(), a.getDisplayName(),
                    vault.decrypt(a.getAccessTokenEnc()));
                for (PublishChannel.RecentPost p : channel.recentPosts(ref, perAccount)) {
                    posts.add(new SocialPost(a.getNetwork(), a.getDisplayName(), p.text(), p.imageUrl(), p.url(), p.postedAt()));
                }
            } catch (ChannelException e) {
                if (e.reauthorize()) {
                    a.setStatus(ChannelAccount.EXPIRED);
                    a.setLastError(e.getMessage());
                    a.setUpdatedAt(Instant.now());
                    accounts.save(a);
                }
                unreadable.add(label + " " + a.getDisplayName() + ": " + e.getMessage());
            } catch (RuntimeException e) {
                log.warn("Reading recent posts of account {} failed", a.getId(), e);
                unreadable.add(label + " " + a.getDisplayName() + ": the posts could not be read.");
            }
        }
        return new Feed(posts, unreadable);
    }

    /** Publishes a job the scheduler has claimed (status RUNNING). Never throws. */
    void run(PublishJob job) {
        Instant now = Instant.now();
        ChannelAccount account = accounts.findById(job.getAccountId()).orElse(null);
        PublishChannel channel = channels.get(job.getProvider());
        if (account == null || channel == null) {
            finish(job, PublishJob.FAILED, null, "The account is no longer connected.");
            return;
        }
        if (!ChannelAccount.CONNECTED.equals(account.getStatus())) {
            finish(job, PublishJob.FAILED, null, account.getDisplayName() + " needs to be connected again.");
            return;
        }
        try {
            List<Media> media = new ArrayList<>();
            for (String path : mediaPaths(job)) {
                media.add(new Media("image/jpeg", blobs.download(path), mediaUrl(path, now)));
            }
            PublishChannel.PublishResult result = channel.publish(
                new ChannelAccountRef(account.getNetwork(), account.getExternalId(), account.getDisplayName(),
                    vault.decrypt(account.getAccessTokenEnc())),
                new ChannelPost(job.getText(), media));
            job.setExternalPostId(result.externalPostId());
            job.setUrl(result.url());
            finish(job, PublishJob.PUBLISHED, null, null);
        } catch (ChannelException e) {
            if (e.reauthorize()) {
                account.setStatus(ChannelAccount.EXPIRED);
                account.setLastError(e.getMessage());
                account.setUpdatedAt(now);
                accounts.save(account);
            }
            retryOrFail(job, e.retryable(), e.getMessage());
        } catch (IOException e) {
            retryOrFail(job, true, "The post's images could not be read.");
        } catch (RuntimeException e) {
            log.warn("Publish job {} failed unexpectedly", job.getId(), e);
            retryOrFail(job, true, "Publishing failed unexpectedly.");
        }
    }

    private void retryOrFail(PublishJob job, boolean retryable, String message) {
        if (retryable && job.getAttempts() < MAX_ATTEMPTS) {
            job.setStatus(PublishJob.QUEUED);
            job.setScheduledAt(Instant.now().plus(backoff(job.getAttempts())));
            job.setError(message);
            jobs.save(job);
        } else {
            finish(job, PublishJob.FAILED, null, message);
        }
    }

    static Duration backoff(int attempts) {
        return Duration.ofMinutes(5L * attempts * attempts);
    }

    private void finish(PublishJob job, String status, String url, String error) {
        job.setStatus(status);
        if (url != null) job.setUrl(url);
        job.setError(error);
        job.setFinishedAt(Instant.now());
        jobs.save(job);
    }

    /**
     * Reads a published post's figures through its channel and keeps them on the job. The read
     * time is kept even when the platform gave nothing, so the post is not asked about again
     * until the next refresh. Never throws.
     */
    void refreshMetrics(PublishJob job, Instant now) {
        job.setMetricsAt(now);
        ChannelAccount account = accounts.findById(job.getAccountId()).orElse(null);
        PublishChannel channel = channels.get(job.getProvider());
        if (account == null || channel == null || !ChannelAccount.CONNECTED.equals(account.getStatus())) {
            jobs.save(job);
            return;
        }
        try {
            Optional<PostMetrics> metrics = channel.metrics(new ChannelAccountRef(account.getNetwork(), account.getExternalId(),
                account.getDisplayName(), vault.decrypt(account.getAccessTokenEnc())), job.getExternalPostId());
            if (metrics.isPresent() && metrics.get().any()) job.setMetricsJson(objectMapper.writeValueAsString(metrics.get()));
        } catch (ChannelException e) {
            if (e.reauthorize()) {
                account.setStatus(ChannelAccount.EXPIRED);
                account.setLastError(e.getMessage());
                account.setUpdatedAt(now);
                accounts.save(account);
            }
        } catch (Exception e) {
            log.warn("Reading the figures of publish job {} failed", job.getId(), e);
        }
        jobs.save(job);
    }

    /**
     * The client's posts published since {@code after} whose {@code sourceRef} starts with
     * {@code sourceRefPrefix}, newest first, with their latest figures. For the module that
     * scheduled them (standing agents) to learn what performs.
     */
    public List<JobView> publishedSince(String clientUserId, String sourceRefPrefix, Instant after) {
        List<PublishJob> list = jobs.findByClientUserIdAndStatusAndSourceRefStartingWithAndFinishedAtAfterOrderByFinishedAtDesc(
            clientUserId, PublishJob.PUBLISHED, sourceRefPrefix, after);
        Map<String, ChannelAccount> byId = accountsById(list);
        return list.stream().map(j -> view(j, byId)).toList();
    }

    /** Accounts whose token has run out are marked so the client sees "connect again" before a post fails. */
    void expireAccounts(Instant now) {
        for (ChannelAccount a : accounts.findByStatusAndExpiresAtLessThan(ChannelAccount.CONNECTED, now)) {
            a.setStatus(ChannelAccount.EXPIRED);
            a.setLastError("The connection expired.");
            a.setUpdatedAt(now);
            accounts.save(a);
        }
    }

    // ---- public media ------------------------------------------------------------------

    String mediaUrl(String blobPath, Instant now) {
        return publicBaseUrl + "/api/channels/media/" + vault.sign(blobPath, now.plus(MEDIA_TTL)) + ".jpg";
    }

    /** An image a platform fetches by URL: only job media, only while its signed link is valid. */
    public Optional<byte[]> publicMedia(String token) {
        String t = token.endsWith(".jpg") ? token.substring(0, token.length() - 4) : token;
        return vault.verify(t, Instant.now())
            .filter(p -> p.startsWith(MEDIA_ROOT) && !p.contains(".."))
            .flatMap(p -> {
                try {
                    return Optional.of(blobs.download(p));
                } catch (IOException e) {
                    return Optional.empty();
                }
            });
    }

    // ---- admin -------------------------------------------------------------------------

    public record AdminStatus(boolean ready, boolean tokenKeySet, String publicBaseUrl, List<ProviderView> providers,
                              long accountsConnected, long accountsExpired, long queued, long failed,
                              List<AdminJob> recent) {}

    public record AdminJob(JobView job, String clientUserId) {}

    public AdminStatus adminStatus(int limit) {
        List<PublishJob> recent = jobs.findAllByOrderByCreatedAtDesc(PageRequest.of(0, Math.max(1, Math.min(200, limit))));
        Map<String, ChannelAccount> byId = accountsById(recent);
        return new AdminStatus(ready(), vault.ready(), publicBaseUrl.isEmpty() ? null : publicBaseUrl, providers(),
            accounts.countByStatus(ChannelAccount.CONNECTED), accounts.countByStatus(ChannelAccount.EXPIRED),
            jobs.countByStatus(PublishJob.QUEUED), jobs.countByStatus(PublishJob.FAILED),
            recent.stream().map(j -> new AdminJob(view(j, byId), j.getClientUserId())).toList());
    }

    // ---- helpers -----------------------------------------------------------------------

    public String redirectUri(String providerId) {
        return publicBaseUrl + "/api/channels/callback/" + providerId;
    }

    private PublishChannel requireChannel(String providerId) {
        PublishChannel c = channels.get(providerId);
        if (c == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown channel");
        return c;
    }

    /** Only same-site paths, so the redirect after connecting cannot be pointed elsewhere. */
    static String safeReturn(String returnTo) {
        if (returnTo == null || returnTo.length() > 500 || !returnTo.startsWith("/") || returnTo.startsWith("//")
                || returnTo.contains("\\") || returnTo.contains("\n") || returnTo.contains("\r")) {
            return "/";
        }
        return returnTo;
    }

    private List<String> mediaPaths(PublishJob job) {
        if (job.getMediaJson() == null) return List.of();
        try {
            return objectMapper.readValue(job.getMediaJson(), new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private ChannelNetwork network(ChannelAccount a) {
        PublishChannel c = requireChannel(a.getProvider());
        return c.networks().stream().filter(n -> n.id().equals(a.getNetwork())).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "That network is no longer supported."));
    }

    private String networkLabel(String provider, String network) {
        PublishChannel c = channels.get(provider);
        if (c == null) return network;
        return c.networks().stream().filter(n -> n.id().equals(network)).map(ChannelNetwork::label).findFirst().orElse(network);
    }

    private Map<String, ChannelAccount> accountsById(List<PublishJob> list) {
        Map<String, ChannelAccount> byId = new HashMap<>();
        accounts.findAllById(list.stream().map(PublishJob::getAccountId).distinct().toList()).forEach(a -> byId.put(a.getId(), a));
        return byId;
    }

    private AccountView view(ChannelAccount a) {
        return new AccountView(a.getId(), a.getProvider(), a.getNetwork(), networkLabel(a.getProvider(), a.getNetwork()),
            a.getDisplayName(), a.getStatus(), a.getExpiresAt(), a.getLastError(), a.getConnectedAt());
    }

    private JobView view(PublishJob j, Map<String, ChannelAccount> byId) {
        ChannelAccount a = byId.get(j.getAccountId());
        return new JobView(j.getId(), j.getAccountId(), a == null ? "Disconnected account" : a.getDisplayName(),
            j.getProvider(), j.getNetwork(), networkLabel(j.getProvider(), j.getNetwork()), j.getSourceRef(),
            j.getScheduledAt(), j.getStatus(), j.getAttempts(), j.getUrl(), j.getError(), j.getCreatedAt(), j.getFinishedAt(),
            metricsOf(j), j.getMetricsAt());
    }

    PostMetrics metricsOf(PublishJob j) {
        if (j.getMetricsJson() == null) return null;
        try {
            return objectMapper.readValue(j.getMetricsJson(), PostMetrics.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
