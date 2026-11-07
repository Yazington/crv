package crv.dto;

/** How a product meets one soft constraint (requirements.md, R3). */
public enum ConstraintMatch {
    MATCH,
    /** Close: for example the colour is one of several on the product. */
    PARTIAL,
    /** The catalog value is missing, or is not a real colour or size. */
    UNKNOWN,
    NO
}
