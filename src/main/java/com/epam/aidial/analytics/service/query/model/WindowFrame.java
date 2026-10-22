package com.epam.aidial.analytics.service.query.model;

/**
 * An explicit window frame: {@code <unit> BETWEEN <start> AND <end>}. Both bounds are required when a
 * frame is given at all; a {@link WindowExpr} with no frame takes the engine's default.
 */
public record WindowFrame(FrameUnit unit, FrameBound start, FrameBound end) {}
