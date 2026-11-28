package ai.genesisbrands.service;

/**
 * Extension point behind the core {@code variantChooser} widget: the client picks which
 * variant of a work item is theirs. The platform owns the endpoint and auth resolution; the
 * tenant owns what choosing means, who may choose and when. For Genesis Brands, it sets the
 * engagement's chosen direction.
 */
public interface ClientVariantChooser {

    /**
     * Makes {@code variantKey} the client's chosen variant of the work item.
     *
     * @return the work item as it now stands
     * @throws java.util.NoSuchElementException if the item or variant does not exist, or is not
     *         this client's
     * @throws org.springframework.web.server.ResponseStatusException with 409 when the item is
     *         not ready to choose from
     */
    ClientWorkItem choose(String workItemId, String variantKey, String clientUserId);
}
