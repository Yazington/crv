package crv.service;

import crv.entity.VariantFields;

/** The result of cleaning one raw record: kept, or skipped for a reason. */
sealed interface CleanOutcome {

    record Kept(VariantFields fields) implements CleanOutcome {}

    record Skipped(SkipReason reason) implements CleanOutcome {}
}
