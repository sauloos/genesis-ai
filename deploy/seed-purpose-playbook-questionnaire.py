#!/usr/bin/env python3
"""Seed the Purpose Playbook discovery questionnaire.

This is the real client-facing instrument behind the Purpose Playbook: seven acts that
collect every signal declared in knowledge/layer1/modules/intake/purpose-playbook-signals.yaml,
in the wording a facilitator would actually use.

Where the wording lives, and why here
-------------------------------------
Layer 1 owns the *contract* — which signals exist, what each one feeds, how to read it. This
script owns the *wording*, because wording is interface copy that gets tuned against real
clients, and a reasoning module that changes every time a question is rephrased stops being a
contract. The signalKeys are the join between the two and must match the YAML exactly.

Two rules from that module are enforced by construction here:
  * No question asks for a playbook field. Nobody is asked for their mission, their value
    proposition or their story arc — they are asked for origin, loss, cost, failure, and the
    agent derives the rest.
  * No question asks a non-designer to name a design attribute. Act 5 asks them to choose
    between things they can see; the tiles, palettes, specimens and slider spectra all come
    from the look-and-feel asset kit (brands/look-kit/.../catalogue.json), which this script
    reads rather than duplicates.

Repeat groups are flattened. The engine has no repeat-group support, so a persona block
becomes three numbered, conditionally-shown question sets keyed belong.persona[0..2].*, and
the same for competitors, admired brands and vision-board detail. The index in the signalKey
is what tells the agent they belong together.

Usage:
    ADMIN_USER=genesis ADMIN_PASS=... GENESIS_API_KEY=... \
        python3 deploy/seed-purpose-playbook-questionnaire.py http://localhost:8099 [--replace]

Idempotent: a questionnaire with this title already present is left alone and reported.
Pass --replace to delete and rebuild it (which also deletes its responses).
"""

import base64
import json
import os
import pathlib
import sys
import urllib.error
import urllib.request

TITLE = "Purpose Playbook discovery"
DESCRIPTION = ("Seven short acts. There are no wrong answers and no design vocabulary "
               "needed — we turn what you say into your Purpose Playbook.")

BASE = (sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("--")
        else "http://localhost:8099").rstrip("/")
REPLACE = "--replace" in sys.argv

ADMIN_USER = os.environ.get("ADMIN_USER", "genesis")
ADMIN_PASS = os.environ.get("ADMIN_PASS")
if not ADMIN_PASS:
    sys.exit("ADMIN_PASS is not set")
AUTH = "Basic " + base64.b64encode(f"{ADMIN_USER}:{ADMIN_PASS}".encode()).decode()
KEY = os.environ.get("GENESIS_API_KEY")
if not KEY:
    sys.exit("GENESIS_API_KEY is not set")

HEADERS = {"Authorization": AUTH, "X-Api-Key": KEY, "Content-Type": "application/json"}
API = BASE + "/api/questionnaires"

REPO = pathlib.Path(__file__).resolve().parent.parent
KIT_PATH = REPO / "brands/look-kit/src/main/resources/static/look-kit/catalogue.json"


def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(API + path, data=data, headers=HEADERS, method=method)
    try:
        with urllib.request.urlopen(req) as response:
            raw = response.read()
    except urllib.error.HTTPError as err:
        sys.exit(f"{method} {path} failed: {err.code} {err.read().decode(errors='replace')}")
    return json.loads(raw) if raw else None


# ── The look-and-feel asset kit ──────────────────────────────────────────────
# Read, never copied: the tiles, hexes, font stacks and slider spectra have one home, and
# LookKit resolves the same values server-side when the agent reads the answers back.

if not KIT_PATH.exists():
    sys.exit(f"Look-kit catalogue not found at {KIT_PATH}")
KIT = json.loads(KIT_PATH.read_text())

PAIRS = KIT["pairs"]
PALETTE_OPTIONS = [{"value": p["value"], "label": p["label"], "note": p.get("note"),
                    "colors": p["colors"]} for p in KIT["palettes"]]
SPECIMEN_OPTIONS = [{"value": t["value"], "label": t["label"], "fontFamily": t["fontFamily"],
                     "sampleText": t["sampleText"], "fallbackText": t["fallbackText"]}
                    for t in KIT["typeSpecimens"]]
SPECTRA = KIT.get("spectra", {})


# ── Acts ──────────────────────────────────────────────────────────────────────

SECTIONS = [
    {"key": "setup", "title": "Get ready",
     "intent": "Who you are and where to look. Thirty seconds."},
    {"key": "belief", "title": "Belief",
     "intent": "Why this business exists, where it's going, and what you won't trade away."},
    {"key": "belong", "title": "Belong",
     "intent": "Who it's for, who holds you up, and where you draw the line."},
    {"key": "best", "title": "Best",
     "intent": "What makes you stand out — as a claim, and as a story."},
    {"key": "beyond", "title": "Beyond",
     "intent": "What this stands for past the people in it."},
    {"key": "look", "title": "Look and feel",
     "intent": "No design words needed. Just point at what you prefer — "
               "we're reading your taste, not taking instructions."},
    {"key": "reflect", "title": "Reflect back",
     "intent": "Here's what we heard. Tell us what we got wrong."},
]


# ── Shared option banks ──────────────────────────────────────────────────────

VALUE_BANK = [
    "honest", "brave", "generous", "careful", "curious", "warm", "direct", "patient",
    "playful", "rigorous", "loyal", "fair", "humble", "ambitious", "calm", "bold",
    "kind", "resourceful", "precise", "open", "reliable", "irreverent", "thoughtful",
    "tenacious", "practical", "optimistic", "disciplined", "welcoming", "independent",
    "collaborative", "protective", "restless", "straight-talking", "gentle",
    "uncompromising", "down-to-earth",
]

# The UN's seventeen Sustainable Development Goals, in their own order. The number IS the
# icon: these goals are numbered in every material the client will ever have seen.
SDGS = [
    ("no-poverty", "No poverty"),
    ("zero-hunger", "Zero hunger"),
    ("good-health", "Good health and wellbeing"),
    ("quality-education", "Quality education"),
    ("gender-equality", "Gender equality"),
    ("clean-water", "Clean water and sanitation"),
    ("clean-energy", "Affordable and clean energy"),
    ("decent-work", "Decent work and economic growth"),
    ("industry-innovation", "Industry, innovation and infrastructure"),
    ("reduced-inequalities", "Reduced inequalities"),
    ("sustainable-cities", "Sustainable cities and communities"),
    ("responsible-consumption", "Responsible consumption and production"),
    ("climate-action", "Climate action"),
    ("life-below-water", "Life below water"),
    ("life-on-land", "Life on land"),
    ("peace-and-justice", "Peace, justice and strong institutions"),
    ("partnerships", "Partnerships for the goals"),
]

VISION_BOARD = ["personal", "impact", "environment", "culture and development",
                "dream team", "give back", "celebration"]


def q(type, prompt, signal, section, step=None, help=None, required=True,
      voice=False, config=None, when=None):
    """One question. `signal` must match a signal_key in purpose-playbook-signals.yaml."""
    return {
        "type": type,
        "prompt": prompt,
        "helpText": help,
        "required": required,
        "allowVoice": voice,
        "configJson": json.dumps(config) if config else None,
        "signalKey": signal,
        "sectionKey": section,
        "stepKey": step,
        "conditionExpr": when,
    }


def text(placeholder, max_length=None):
    config = {"placeholder": placeholder}
    if max_length:
        config["maxLength"] = max_length
    return config


def choices(options):
    return {"options": options}


def slider(low, high, spectrum=None):
    config = {"min": 1, "max": 5, "step": 1, "minLabel": low, "maxLabel": high}
    colours = SPECTRA.get(spectrum) if spectrum else None
    if colours:
        config["trackColors"] = colours
    return config


QUESTIONS = []


# ── Act 0 — Get ready ────────────────────────────────────────────────────────

QUESTIONS += [
    q("SHORT_TEXT", "What's the business called?", "setup.businessName", "setup", "s-who",
      config=text("Cre8ion")),
    q("SHORT_TEXT", "And who are we talking to — your name and role?",
      "setup.respondent", "setup", "s-who", config=text("Darrell, founder")),
    q("LONG_TEXT", "Why is the business called that? What should the name make people think of?",
      "setup.nameMeaning", "setup", "s-name", voice=True,
      help="The real reason, even if it's simple. We build the logo on what the name means "
           "to you, not on what we might read into it."),
    q("SHORT_TEXT",
      "Any other versions of the name you use, or would happily use?",
      "setup.nameVersions", "setup", "s-name", required=False,
      help="A shorter one, or one that says what you do — for example 'Hearth' and "
           "'Hearth Bakery'. Leave out Ltd or LLC; that's for contracts, not the "
           "logo. We'll suggest some too.",
      config=text("Hearth · Hearth Bakery", 240)),

    q("SHORT_TEXT",
      "In one sentence, as you'd say it to a stranger at a bus stop — what do you do?",
      "setup.oneLiner", "setup", "s-oneliner",
      help="We'll show you this sentence again later, set in different typefaces, "
           "so you're judging type with your own words.",
      config=text("We make the thing people come back for.", 240)),

    q("SINGLE_SELECT", "Do you want to be the public face of the brand?",
      "setup.publicFace", "setup", "s-who", required=False,
      help="If yes, your name can appear in the brand story, the About page and the sign-off. "
           "If no, everything speaks as the brand and never names you.",
      config=choices(["yes", "no", "not sure"])),

    q("SHORT_TEXT", "Which sector are you in?", "setup.sector", "setup", "s-shape",
      config=text("Childcare franchising")),
    q("SINGLE_SELECT", "Where are you up to?", "setup.stage", "setup", "s-shape",
      config=choices(["pre-launch", "trading under a year", "one to three years",
                      "three to ten years", "ten years or more"])),
    q("SHORT_TEXT", "How many of you are there?", "setup.teamSize", "setup", "s-shape",
      config=text("Just me / 4 / about 30")),

    q("URL", "Do you have a website or a social profile we can look at?",
      "setup.website", "setup", "s-web", required=False,
      help="We'll read it ourselves rather than ask you to describe it.",
      config=text("https://…")),
]


# ── Act 1 — Belief ───────────────────────────────────────────────────────────

QUESTIONS += [
    q("LONG_TEXT", "Why does this business exist — what made you start it, or take it on?",
      "belief.origin", "belief", "b-origin", voice=True,
      help="However it actually happened. The boring version is usually the true one."),

    q("LONG_TEXT", "What would be lost if your business disappeared tomorrow?",
      "belief.loss", "belief", "b-loss",
      help="Not what you'd lose — what the world would lose."),

    # max_select 2 in the signal module. CHIP_PICK is the only type that enforces a cap and
    # lets a client add a word we didn't think of, which is the point of a prompt list.
    q("CHIP_PICK", "Which of these is closest to what you really do for people?",
      "belief.cause.call", "belief", "b-cause-1",
      help="Two at most.",
      config={"maxSelect": 2, "addPlaceholder": "Or your own word…",
              "bank": ["lead", "help", "change", "guide", "build", "protect",
                       "teach", "connect"]}),
    q("SINGLE_SELECT", "Do you mostly create, craft, or connect?",
      "belief.cause.utilize", "belief", "b-cause-1",
      config=choices(["create", "craft", "connect"])),

    q("LONG_TEXT", "What's your superpower — the thing you're better at than almost anyone?",
      "belief.cause.ability", "belief", "b-cause-2"),

    q("LONG_TEXT", "When you've done your job well, what exists in the world that didn't before?",
      "belief.cause.solution", "belief", "b-cause-3"),
    q("SINGLE_SELECT", "And where does that change happen?",
      "belief.cause.execute", "belief", "b-cause-3",
      config=choices(["locally", "regionally", "nationally", "globally",
                      "online everywhere"])),

    q("LONG_TEXT", "Where are you now?", "belief.now", "belief", "b-arc",
      help="Honestly. This is the starting point we measure the plan against."),
    q("LONG_TEXT", "And where do you aspire to be?", "belief.aspire", "belief", "b-arc"),

    q("SHORT_TEXT", "What's the first step that gets you there?",
      "belief.firstStep", "belief", "b-step"),
    q("SHORT_TEXT", "One ambitious goal you'd own for the next five years. "
                    "Finish this: in five years, we will…",
      "belief.growthGoal", "belief", "b-step",
      help="A number and a date, if you can. A vision without a deadline is a wish."),
    q("SHORT_TEXT", "Finish this: we want to be known as the best in…",
      "belief.leadingPosition", "belief", "b-step", required=False),

    q("CHIP_PICK", "Which of these feel like you? Pick eight.",
      "belief.valueChips", "belief", "b-chips",
      help="If your word isn't here, add it — this list is a prompt, not a vocabulary.",
      config={"maxSelect": 8, "addPlaceholder": "Add your own word…", "bank": VALUE_BANK}),

    q("RANKING", "Now put your top five in order.",
      "belief.valueRanked", "belief", "b-rank",
      help="Tap them in order, most important first.",
      config={"sourceSignal": "belief.valueChips", "pick": 5}),

    q("LONG_TEXT", "Tell me about a time one of these cost you money, and you did it anyway.",
      "belief.valueCost", "belief", "b-cost", voice=True,
      help="This is the most useful answer in the whole questionnaire. A value you've paid "
           "for is a value; the rest is a poster."),

    q("SHORT_TEXT", "Three words you'd never want used about your brand.",
      "belief.neverWords", "belief", "b-never",
      help="We carry these forward as a veto on everything we make.",
      config=text("cheap, corporate, pushy")),
]


# ── Act 2 — Belong ───────────────────────────────────────────────────────────

QUESTIONS.append(
    q("SINGLE_SELECT", "How many distinctly different types of customer do you serve?",
      "belong.segmentCount", "belong", "g-count",
      help="Different types, not different people.",
      config=choices(["1", "2", "3 or more"])))

# Three persona blocks. The second and third only open if the client said they have them,
# which is what belong.segmentCount is for.
PERSONA_GATES = [None, "belong.segmentCount != '1'", "belong.segmentCount == '3 or more'"]
PERSONA_ORDINALS = ["favourite", "second", "third"]

for i, gate in enumerate(PERSONA_GATES):
    label = PERSONA_ORDINALS[i]
    prompt = ("Picture your favourite customer. Tell me about them — who they are, what "
              "they're trying to do, and what drives them mad."
              if i == 0 else
              f"Now your {label} type of customer. Same thing — who are they, what are they "
              "after, and what drives them mad?")
    QUESTIONS += [
        q("LONG_TEXT", prompt, f"belong.persona[{i}].story", "belong", f"g-p{i}-story",
          voice=True, when=gate,
          help="One real person is worth more than a category. We'll turn this into a "
               "persona — you don't need to fill in boxes."),
        q("SHORT_TEXT", "Roughly how old are they?", f"belong.persona[{i}].ageBand",
          "belong", f"g-p{i}-detail", when=gate, config=text("30s to 40s")),
        q("SHORT_TEXT", "And where are they?", f"belong.persona[{i}].location",
          "belong", f"g-p{i}-detail", when=gate, config=text("Greater Manchester")),
        q("SINGLE_SELECT", "When they choose someone like you, what decides it?",
          f"belong.persona[{i}].decisionDriver", "belong", f"g-p{i}-detail", when=gate,
          config=choices(["price", "trust", "speed", "status", "referral"])),
    ]

QUESTIONS += [
    q("LONG_TEXT", "What do they usually try before they find you, and why does it fail them?",
      "belong.failedAlternatives", "belong", "g-fail",
      help="The thing they tried that didn't work is where your brand starts."),

    q("LONG_TEXT", "Paste something a customer actually said to you after you delivered.",
      "belong.customerQuote", "belong", "g-voice", required=False,
      help="Their words, not tidied up. Their vocabulary is the one we write in."),
    q("MULTI_SELECT", "Which generations are your customers?",
      "belong.generations", "belong", "g-voice", required=False,
      config=choices(["Baby Boomers (1946–64)", "Gen X (1965–76)",
                      "Gen Y (1977–95)", "Gen Z (1996–)"])),

    q("SHORT_TEXT", "Who keeps you well?", "belong.innerCircle.wellbeing",
      "belong", "g-circle", required=False,
      help="First names are plenty. Five people who hold you up — "
           "leave any blank if there isn't one.", config=text("First name")),
    q("SHORT_TEXT", "Who gives you direction?", "belong.innerCircle.direction",
      "belong", "g-circle", required=False, config=text("First name")),
    q("SHORT_TEXT", "Who helps you get seen?", "belong.innerCircle.visibility",
      "belong", "g-circle", required=False, config=text("First name")),
    q("SHORT_TEXT", "Who holds you accountable?", "belong.innerCircle.accountability",
      "belong", "g-circle", required=False, config=text("First name")),
    q("SHORT_TEXT", "And who keeps you grounded?", "belong.innerCircle.grounding",
      "belong", "g-circle", required=False, config=text("First name")),

    q("LONG_TEXT", "What will you absolutely not do, even for good money?",
      "belong.lineOfCircle", "belong", "g-line",
      help="Where you draw the line is what gives the brand an edge to have."),
    q("LONG_TEXT", "Why should they pick you over someone who does the same thing?",
      "belong.uniqueReason", "belong", "g-line"),

    q("SINGLE_SELECT", "Is the person who pays you the same as the person who benefits?",
      "belong.buyerIsUser", "belong", "g-buyer", config=choices(["yes", "no"])),

    q("SHORT_TEXT", "Who signs it off — what's their role?", "belong.buyer.role",
      "belong", "g-buyer-detail", required=False, when="belong.buyerIsUser == 'no'",
      config=text("Operations director")),
    q("LONG_TEXT", "What are they personally risking by choosing you?",
      "belong.buyer.risk", "belong", "g-buyer-detail", required=False,
      when="belong.buyerIsUser == 'no'"),

    q("MULTI_SELECT", "Where do they first run into you?",
      "belong.touchpoints", "belong", "g-touch", required=False,
      config=choices(["search", "referral", "social", "events", "directory",
                      "outbound", "walk-past", "press"])),
]


# ── Act 3 — Best ─────────────────────────────────────────────────────────────

# The five-layer ladder, asked as one six-blank sentence on a single screen — the template's
# own device. Genesis rewrites the blanks into a publishable proposition afterwards.
QUESTIONS += [
    q("SHORT_TEXT", "Our…", "best.ladder.offer", "best", "t-ladder",
      help="One sentence, six blanks. Don't polish it — we'll do that.",
      config=text("franchise")),
    q("SHORT_TEXT", "…solves…", "best.ladder.problem", "best", "t-ladder",
      config=text("the lack of quality activity-based childcare")),
    q("SHORT_TEXT", "…for…", "best.ladder.audience", "best", "t-ladder",
      config=text("ambitious entrepreneurs")),
    q("SHORT_TEXT", "…using…", "best.ladder.method", "best", "t-ladder",
      config=text("a scalable turnkey model")),
    q("SHORT_TEXT", "…which gets them…", "best.ladder.result", "best", "t-ladder",
      config=text("a business of their own that pays")),
    q("SHORT_TEXT", "…in…", "best.ladder.timeframe", "best", "t-ladder",
      config=text("as little as 30 days")),

    q("SHORT_TEXT", "Finish this: we're the only ones who…", "best.usp", "best", "t-usp"),
    q("SINGLE_SELECT", "In your brand's story, who's the hero — you, or the customer?",
      "best.hero", "best", "t-usp", config=choices(["the customer", "us", "both"])),

    q("LONG_TEXT", "Every good story has an adversary. What are you fighting — "
                   "the thing that makes this work necessary?",
      "best.villain", "best", "t-villain",
      help="A condition, a habit, an industry default. Rarely a company."),

    q("LONG_TEXT", "Tell me about a time it went wrong, and what you did about it.",
      "best.failureStory", "best", "t-failure", voice=True,
      help="This is where testimonials come from. A story with no failure in it reads "
           "like a brochure."),

    q("SHORT_TEXT", "And what does victory look like for the customer?",
      "best.victory", "best", "t-victory"),
]

# Three competitors are required, five are allowed. There's no "add another" control, so the
# fourth and fifth sit on their own optional screen rather than behind a gate question the
# signal module doesn't declare.
for i in range(5):
    optional = i >= 3
    step = f"t-comp-{i}"
    first = i == 0
    QUESTIONS += [
        q("SHORT_TEXT",
          "Who else do they consider?" if first else f"Competitor {i + 1}",
          f"best.competitor[{i}].name", "best", step, required=not optional,
          help=("Three at least — the ones that actually come up in a sales conversation."
                if first else
                "Only if there is one. Leave this screen blank otherwise."
                if i == 3 else None),
          config=text("Their name")),
        q("URL", "Their website?", f"best.competitor[{i}].url", "best", step,
          required=False, help="We'll look at it ourselves." if first else None,
          config=text("https://…")),
        q("SHORT_TEXT", "What do they do well?", f"best.competitor[{i}].strengths",
          "best", step, required=not optional),
        q("SHORT_TEXT", "And what don't they do well?", f"best.competitor[{i}].weaknesses",
          "best", step, required=not optional),
    ]

QUESTIONS.append(
    q("SHORT_TEXT", "What's the one thing people get wrong about you?",
      "best.misconception", "best", "t-misc", required=False))


# ── Act 4 — Beyond ───────────────────────────────────────────────────────────

QUESTIONS += [
    q("ICON_GRID",
      "Of the UN's seventeen Sustainable Development Goals, pick the three you want to be "
      "known for.", "beyond.sdgs", "beyond", "y-sdg",
      help="Three. Not the ones that sound best — the ones you'd actually act on.",
      config={"columns": 3, "maxSelect": 3,
              "options": [{"value": value, "label": label, "icon": str(n + 1)}
                          for n, (value, label) in enumerate(SDGS)]}),

    q("CHIP_PICK", "Which of these matter most over the next few years? Pick three.",
      "beyond.visionBoardPicks", "beyond", "y-vision",
      config={"maxSelect": 3, "addPlaceholder": "Something else…", "bank": VISION_BOARD}),
]

# One follow-up per vision-board theme, each shown only if that theme was picked. Flattened
# from the visionBoardPick repeat group; the index matches the chip order above.
for i, pick in enumerate(VISION_BOARD):
    QUESTIONS.append(
        q("SHORT_TEXT", f"One line on {pick} — what would good look like?",
          f"beyond.visionBoardDetail[{i}]", "beyond", "y-vision-detail",
          when=f"beyond.visionBoardPicks contains '{pick}'",
          help="A picture, not a KPI." if i == 0 else None))

QUESTIONS += [
    q("LONG_TEXT", "In ten years, what do you want people to say about you when you're not "
                   "in the room?", "beyond.legacy", "beyond", "y-legacy"),

    q("SHORT_TEXT", "Is there a cause, a community or a place this brand should be tied to?",
      "beyond.giveBack", "beyond", "y-give", required=False),
    q("SHORT_TEXT", "Are you working towards any certification — B Corp, ISO, an industry "
                    "accreditation?", "beyond.accreditation", "beyond", "y-give",
      required=False),
]


# ── Act 5 — Look and feel ────────────────────────────────────────────────────
# Sliders set the territory, the forced-choice pairs locate the point inside it, and the
# dislikes bound it. Nothing here asks the client to name a design attribute.

QUESTIONS += [
    q("SLIDER", "Does it lean classic, or contemporary?", "look.slider.era", "look",
      "l-slide-1", help="Seven of these. Go on instinct — there's no right answer.",
      config=slider("Classic", "Contemporary")),
    q("SLIDER", "Quiet and understated, or bold and loud?", "look.slider.volume", "look",
      "l-slide-1", config=slider("Quiet and understated", "Bold and loud")),
    q("SLIDER", "Warm, or cool?", "look.slider.temperature", "look", "l-slide-1",
      config=slider("Warm", "Cool", "look.slider.temperature")),

    q("SLIDER", "Playful, or serious?", "look.slider.mood", "look", "l-slide-2",
      config=slider("Playful", "Serious")),
    q("SLIDER", "Premium and crafted, or accessible and everyday?", "look.slider.market",
      "look", "l-slide-2", config=slider("Premium and crafted", "Accessible and everyday")),

    q("SLIDER", "Simple and minimal, or rich and detailed?", "look.slider.density", "look",
      "l-slide-3", config=slider("Simple and minimal", "Rich and detailed")),
    q("SLIDER", "Corporate, or human?", "look.slider.formality", "look", "l-slide-3",
      config=slider("Corporate", "Human")),
]

# Eight forced-choice pairs, two to a screen. No neutral option: the signal lives in the
# choice. Axes, labels and artwork all come from the asset kit.
for i, pair in enumerate(PAIRS):
    QUESTIONS.append(
        q("IMAGE_CHOICE", "Which of these two feels more like you?",
          pair["signalKey"], "look", f"l-pairs-{i // 2}",
          help="Neither is better. Pick the one you'd rather live with."
               if i == 0 else None,
          config={"columns": 2, "maxSelect": 1,
                  "options": [{"value": tile["value"], "label": tile["label"],
                               "imageUrl": tile["imageUrl"]}
                              for tile in (pair["a"], pair["b"])]}))

QUESTIONS += [
    q("PALETTE_CHOICE", "Two you'd be happy to live with for the next ten years.",
      "look.palette.like", "look", "l-palette",
      config={"columns": 3, "maxSelect": 2, "options": PALETTE_OPTIONS}),

    q("PALETTE_CHOICE", "And one you'd hate.", "look.palette.dislike", "look", "l-palette-no",
      help="This one matters more than the two you liked — it rules out a whole territory "
           "in one tap.",
      config={"columns": 3, "maxSelect": 1, "options": PALETTE_OPTIONS}),
    q("SHORT_TEXT", "Any colour that's completely off the table — a competitor's, or one you "
                    "just can't stand?", "look.palette.forbidden", "look", "l-palette-no",
      required=False, config=text("Anything near their green")),

    q("TYPE_SPECIMEN", "Here's your own sentence, set four ways. Which one sounds like you "
                       "saying it?", "look.typeSpecimen", "look", "l-type",
      config={"columns": 2, "options": SPECIMEN_OPTIONS}),

    q("FILE_UPLOAD", "Got a logo already? Drop it in — any format.",
      "look.currentLogo", "look", "l-logo", required=False,
      help="AI, EPS, PDF, PNG, a photo of a sign — whatever you have.",
      config={"accept": "", "maxFiles": 6, "cta": "Click to choose your logo files"}),
]

# Deliberately on its own screen: the condition can only be evaluated once the upload has
# been saved, which happens when the client leaves the screen above.
QUESTIONS.append(
    q("SINGLE_SELECT", "And what should we do with it?", "look.logoIntent", "look",
      "l-logo-intent", when="look.currentLogo is present",
      help="This tells us where your centre of gravity is — we'll still show you all three "
           "directions.",
      config=choices(["keep it", "evolve it", "start fresh", "not sure"])))

# Same screen and condition: the logo assessment page after discovery reads this alongside the
# file, so its verdict answers what the client already likes and dislikes.
QUESTIONS.append(
    q("LONG_TEXT", "How do you feel about it? What do you like, and what bothers you?",
      "look.currentLogoFeelings", "look", "l-logo-intent", when="look.currentLogo is present",
      required=False, voice=True,
      help="A line or two is plenty: where it came from, what you'd never lose, what never felt right.",
      config=text("We love the leaf, but the lettering always felt dated")))

QUESTIONS.append(
    q("FILE_UPLOAD", "Any colours, fonts or materials already in use that we should know "
                     "about?", "look.existingAssets", "look", "l-assets", required=False,
      help="Brand guidelines, a signage photo, a packaging sample — anything in use.",
      config={"accept": "", "maxFiles": 10, "cta": "Click to choose files"}))

# Three admired brands. Taste, never a template — and the interface says so, because a client
# who thinks they're commissioning an imitation answers differently.
for i in range(3):
    first = i == 0
    QUESTIONS += [
        q("SHORT_TEXT",
          "Name a brand you admire — in any sector." if first
          else f"Another one. Brand {i + 1}.",
          f"look.admired[{i}].name", "look", f"l-admired-{i}",
          help="Three of these. We're reading what you're drawn to, not planning to copy "
               "anyone — none of these will show up in your brand."
               if first else None,
          config=text("Any brand, any sector")),
        q("URL", "Where can we see them?", f"look.admired[{i}].url", "look",
          f"l-admired-{i}", required=False, config=text("https://…")),
        q("LONG_TEXT", "What is it about them?", f"look.admired[{i}].why", "look",
          f"l-admired-{i}",
          help="The feeling, not the logo." if first else None),
    ]

QUESTIONS += [
    q("MULTI_SELECT", "Where will this brand actually have to live?",
      "look.placements", "look", "l-place",
      help="Everything you tick, we design for and cost. Everything you don't, we don't.",
      config=choices(["website", "shopfront", "vehicle livery", "packaging", "uniforms",
                      "exhibition stand", "social", "app", "signage", "print",
                      "merchandise"])),
    q("SHORT_TEXT", "Anything we absolutely must keep? An accreditation mark, a founder's "
                    "name, a regulatory badge?", "look.mustKeep", "look", "l-place",
      required=False),
]


# ── Act 6 — Reflect back ─────────────────────────────────────────────────────
# Said back to them, in their own words, for correction. A client correcting a draft gives
# better data than a client facing an empty box, and what they change is itself signal.
# Templates stitch only free-text answers: a client should never be shown an internal key.

QUESTIONS += [
    q("SYNTHESIS", "Here's what I'm hearing. Is this you?", "reflect.mission", "reflect",
      "r-mission",
      config={"rows": 6, "template":
              "{setup.businessName} exists to {belief.cause.call} people, "
              "{belief.cause.execute}. We're at our best at this: {belief.cause.ability} "
              "If we disappeared tomorrow, this is what would be lost: {belief.loss}",
              "note": "This is our wording, built from your answers. Change anything that "
                      "doesn't sound like you — what you change tells us as much as what "
                      "you keep."}),

    q("SYNTHESIS", "And your values, in your order.", "reflect.values", "reflect", "r-values",
      config={"rows": 6, "template":
              "In order: {belief.valueRanked}. The one we've actually paid for: "
              "{belief.valueCost} And never: {belief.neverWords}.",
              "note": "Each of these becomes a sentence about how you behave, not a word on "
                      "a wall. Correct anything that's in the wrong order."}),

    q("SYNTHESIS", "Here's the claim, assembled.", "reflect.valueProposition", "reflect",
      "r-vp",
      config={"rows": 6, "template":
              "Our {best.ladder.offer} solves {best.ladder.problem} for "
              "{best.ladder.audience}, using {best.ladder.method}, which gets them "
              "{best.ladder.result} in {best.ladder.timeframe}. We're the only ones who "
              "{best.usp}.",
              "note": "Rough on purpose — we'll make it publishable. Fix anything that's "
                      "factually wrong or overclaims."}),

    q("SYNTHESIS", "And the feel we're taking forward.", "reflect.look", "reflect", "r-look",
      config={"rows": 6, "template":
              "You admire {look.admired[0].name}, for this reason: {look.admired[0].why} "
              "You'd never want this brand called {belief.neverWords}. Off the table: "
              "{look.palette.forbidden}. We keep {look.mustKeep}. And it has to work on "
              "{look.placements}.",
              "note": "Your colour, type and tile choices are recorded separately — this is "
                      "just the part we want to say back in words."}),
]


# ── Seed ─────────────────────────────────────────────────────────────────────

def main():
    existing = next((item for item in (call("GET", "") or []) if item["title"] == TITLE), None)
    if existing:
        if not REPLACE:
            print(f"Already present: \"{TITLE}\" ({existing['id']}) — nothing to do.")
            print("Pass --replace to delete and rebuild it (this deletes its responses too).")
            return
        print(f"Replacing \"{TITLE}\" ({existing['id']})…")
        call("DELETE", f"/{existing['id']}")

    questionnaire = call("POST", "", {"title": TITLE, "description": DESCRIPTION})
    qid = questionnaire["id"]
    call("PUT", f"/{qid}", {"title": TITLE, "description": DESCRIPTION,
                            "sectionsJson": json.dumps(SECTIONS)})
    print(f"Created {qid} with {len(SECTIONS)} acts.")

    for question in QUESTIONS:
        call("POST", f"/{qid}/questions", question)
    print(f"Added {len(QUESTIONS)} questions.")

    report = call("GET", f"/{qid}/validation")
    if report["ready"]:
        print("Publish-ready: no issues.")
    else:
        print("NOT publish-ready:")
        for issue in report["issues"]:
            print(f"  - {issue}")
        sys.exit(1)

    print()
    print("Left unpublished on purpose. To publish:")
    print(f"  curl -X POST {BASE}/api/questionnaires/{qid}/publish \\")
    print("    -H 'Content-Type: application/json' -H \"X-Api-Key: $GENESIS_API_KEY\" \\")
    print("    -u \"$ADMIN_USER:$ADMIN_PASS\" -d '{}'")
    print()
    print("To walk it in the admin preview:")
    print(f"  {BASE}/dashboard/widget-preview?type=questionnaire"
          f"&config=%7B%22questionnaireId%22%3A%22{qid}%22%7D")


if __name__ == "__main__":
    main()
