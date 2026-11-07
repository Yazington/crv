package crv.dto;

/** What the runtime photo check saw for the requested colour. Used only to order results (AC1.6). */
public enum PhotoMatch {
    YES,
    PARTLY,
    NO,
    /** No colour was asked for, the product was outside the top results, or the check failed. */
    NOT_CHECKED
}
