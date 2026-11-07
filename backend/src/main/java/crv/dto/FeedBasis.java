package crv.dto;

/** Why the homepage shows what it shows. */
public enum FeedBasis {
    /** No clicks yet: random products, complete listings more likely. */
    RANDOM,
    /** Products like the ones the shopper clicked last. */
    HISTORY
}
