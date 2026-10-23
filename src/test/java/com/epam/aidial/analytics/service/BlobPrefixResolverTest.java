package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.configuration.SyncProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies {@link BlobPrefixResolver} turns a UTC date into the whole-day prefix, turns a start-epoch
 * range into the "recent time slice" key prefixes the band-mode poller lists, refuses a date-directory
 * layout that cannot work, and reports a file name the configured layout cannot match. Reference epochs:
 * midnight 2026-06-24 = 1782259200, midnight 2026-06-25 = 1782345600.
 *
 * <p>The default-layout expectations are deliberately verbatim what they were before either layout became
 * configurable: they are the check that {@code yyyy/MM/dd} plus {@code 'date='yyyy-MM-dd} reproduces the
 * previous prefixes exactly.
 */
class BlobPrefixResolverTest {

    private static final String DEFAULT_PATTERN = "yyyy/MM/dd";
    private static final String DEFAULT_HEAD = "'date='yyyy-MM-dd";

    private static BlobPrefixResolver resolver(String pathPrefix, String datePathPattern) {
        return resolver(pathPrefix, datePathPattern, DEFAULT_HEAD, new SimpleMeterRegistry());
    }

    private static BlobPrefixResolver resolver(String pathPrefix, String datePathPattern, String fileNamePrefixPattern) {
        return resolver(pathPrefix, datePathPattern, fileNamePrefixPattern, new SimpleMeterRegistry());
    }

    private static BlobPrefixResolver resolver(
            String pathPrefix, String datePathPattern, String fileNamePrefixPattern, MeterRegistry meterRegistry) {
        return new BlobPrefixResolver(new SyncProperties(
                "-", 1, 10, 268_435_456L, SyncProperties.DEFAULT_MAX_LINE_BYTES, pathPrefix, datePathPattern, fileNamePrefixPattern, false,
                List.of(new SyncProperties.Target("dial_usage_log", "classpath:mapping/dial-usage-log.yaml")), null, null, null),
                meterRegistry);
    }

    @Test
    void rangeWithinOneBandYieldsOnePrefix() {
        // 1782297030 (10:30:30) .. 1782297600 (10:40:00) both floor to band 1782297.
        List<String> prefixes = resolver("logs", DEFAULT_PATTERN).bandPrefixes(
                Instant.ofEpochSecond(1782297030), Instant.ofEpochSecond(1782297600), 1000);
        assertThat(prefixes).containsExactly("logs/2026/06/24/date=2026-06-241782297");
    }

    @Test
    void rangeStraddlingDecimalBoundaryYieldsCurrentAndPreviousBands() {
        // 1782297030 -> band 1782297, 1782298200 (10:50:00) -> band 1782298.
        List<String> prefixes = resolver("logs", DEFAULT_PATTERN).bandPrefixes(
                Instant.ofEpochSecond(1782297030), Instant.ofEpochSecond(1782298200), 1000);
        assertThat(prefixes).containsExactly(
                "logs/2026/06/24/date=2026-06-241782297",
                "logs/2026/06/24/date=2026-06-241782298");
    }

    @Test
    void widerRangeYieldsOnePrefixPerBandItTouches() {
        // A ~40-minute range over 1000s (~16.7m) bands -> 3 adjacent bands (not a single coarse one).
        List<String> prefixes = resolver("logs", DEFAULT_PATTERN).bandPrefixes(
                Instant.ofEpochSecond(1782297030), Instant.ofEpochSecond(1782299400), 1000);
        assertThat(prefixes).containsExactly(
                "logs/2026/06/24/date=2026-06-241782297",
                "logs/2026/06/24/date=2026-06-241782298",
                "logs/2026/06/24/date=2026-06-241782299");
    }

    @Test
    void bandStraddlingMidnightYieldsPrefixForEachDay() {
        // Band 1782345 spans 1782345000 (2026-06-24 23:50:00) .. 1782345999 (2026-06-25 00:06:39).
        List<String> prefixes = resolver("logs", DEFAULT_PATTERN).bandPrefixes(
                Instant.ofEpochSecond(1782345500), Instant.ofEpochSecond(1782345800), 1000);
        assertThat(prefixes).containsExactlyInAnyOrder(
                "logs/2026/06/24/date=2026-06-241782345",
                "logs/2026/06/25/date=2026-06-251782345");
    }

    @Test
    void blankPathPrefixOmitsTheBasePath() {
        List<String> prefixes = resolver("", DEFAULT_PATTERN).bandPrefixes(
                Instant.ofEpochSecond(1782297030), Instant.ofEpochSecond(1782297600), 1000);
        assertThat(prefixes).containsExactly("2026/06/24/date=2026-06-241782297");
    }

    @Test
    void emptyWhenUpperBoundPrecedesLowerBound() {
        List<String> prefixes = resolver("logs", DEFAULT_PATTERN).bandPrefixes(
                Instant.ofEpochSecond(1782297600), Instant.ofEpochSecond(1782297030), 1000);
        assertThat(prefixes).isEmpty();
    }

    @Test
    void bandWiderThanOneDayIsRefused() {
        // Band 17826 at a 100000s width runs 2026-06-27 07:20:00 .. 2026-06-29 11:06:39, so it straddles
        // three date directories while only the first and last are enumerated — 2026-06-28's blobs would be
        // unreachable by any emitted prefix, and the poller would report healthy cycles discovering none of
        // them. Refused at the boundary instead, where it is a loud precondition rather than a silent gap.
        assertThatThrownBy(() -> resolver("logs", DEFAULT_PATTERN).bandPrefixes(
                Instant.ofEpochSecond(1782600000), Instant.ofEpochSecond(1782699999), 100_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not exceed one day");
    }

    @Test
    void widthThatIsNotPowerOfTenIsRefused() {
        assertThatThrownBy(() -> resolver("logs", DEFAULT_PATTERN).bandPrefixes(
                Instant.ofEpochSecond(1782297030), Instant.ofEpochSecond(1782297600), 600))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive power of ten");
    }

    @Test
    void theWidestAcceptedBandStillCoversBothDaysItTouches() {
        // 10000s is the widest power of ten within the bound; a band of it can span two days but never three.
        List<String> prefixes = resolver("logs", DEFAULT_PATTERN).bandPrefixes(
                Instant.ofEpochSecond(1782345500), Instant.ofEpochSecond(1782345500), 10_000);
        assertThat(prefixes).containsExactlyInAnyOrder(
                "logs/2026/06/24/date=2026-06-24178234",
                "logs/2026/06/25/date=2026-06-25178234");
    }

    @Test
    void dayPrefixCarriesDefaultLayoutAndTrailingSlash() {
        assertThat(resolver("logs", DEFAULT_PATTERN).dayPrefix(LocalDate.of(2026, 6, 18)))
                .isEqualTo("logs/2026/06/18/");
    }

    @Test
    void singleSegmentLayoutPlacesTheWholeDateInOneDirectory() {
        BlobPrefixResolver flat = resolver("your_dir", "yyyy-MM-dd");
        assertThat(flat.dayPrefix(LocalDate.of(2026, 6, 18))).isEqualTo("your_dir/2026-06-18/");
        // The file-name portion of a band prefix is the producer's and stays exactly as it was.
        assertThat(flat.bandPrefixes(Instant.ofEpochSecond(1782297030), Instant.ofEpochSecond(1782297600), 1000))
                .containsExactly("your_dir/2026-06-24/date=2026-06-241782297");
    }

    @Test
    void compactLayoutIsSupported() {
        assertThat(resolver("logs", "yyyyMMdd").dayPrefix(LocalDate.of(2026, 6, 18)))
                .isEqualTo("logs/20260618/");
    }

    @Test
    void blankPathPrefixWithSingleSegmentLayoutOmitsTheBasePath() {
        assertThat(resolver("", "yyyy-MM-dd").dayPrefix(LocalDate.of(2026, 6, 18)))
                .isEqualTo("2026-06-18/");
    }

    @Test
    void trailingSlashInTheLayoutDoesNotDoubleUp() {
        BlobPrefixResolver trailing = resolver("logs", "yyyy-MM-dd/");
        assertThat(trailing.dayPrefix(LocalDate.of(2026, 6, 18))).isEqualTo("logs/2026-06-18/");
        assertThat(trailing.bandPrefixes(Instant.ofEpochSecond(1782297030), Instant.ofEpochSecond(1782297600), 1000))
                .containsExactly("logs/2026-06-24/date=2026-06-241782297");
    }

    @Test
    void trailingSlashOnTheBasePathDoesNotDoubleUp() {
        assertThat(resolver("logs/", DEFAULT_PATTERN).dayPrefix(LocalDate.of(2026, 6, 18)))
                .isEqualTo("logs/2026/06/18/");
    }

    @Test
    void leadingSlashOnTheBasePathIsDropped() {
        // No producer writes keys beginning with '/', so keeping one would list nothing while the run
        // still reported success — the silent-zero-ingest outcome this resolver exists to rule out.
        BlobPrefixResolver rooted = resolver("/logs/", DEFAULT_PATTERN);
        assertThat(rooted.dayPrefix(LocalDate.of(2026, 6, 18))).isEqualTo("logs/2026/06/18/");
        assertThat(rooted.bandPrefixes(Instant.ofEpochSecond(1782297030), Instant.ofEpochSecond(1782297600), 1000))
                .containsExactly("logs/2026/06/24/date=2026-06-241782297");
    }

    @Test
    void basePathOfOnlySlashesIsTreatedAsBlank() {
        assertThat(resolver("/", DEFAULT_PATTERN).dayPrefix(LocalDate.of(2026, 6, 18)))
                .isEqualTo("2026/06/18/");
    }

    @Test
    void quotedLiteralInTheLayoutSurvives() {
        // Slashes come off the rendered segment, not the pattern, so a quoted literal keeps its own.
        assertThat(resolver("logs", "'dt/'yyyy-MM-dd").dayPrefix(LocalDate.of(2026, 6, 18)))
                .isEqualTo("logs/dt/2026-06-18/");
    }

    @Test
    void unparsableLayoutIsRefused() {
        // An unterminated literal: DateTimeFormatter refuses the pattern outright.
        assertThatThrownBy(() -> resolver("logs", "yyyy/'MM"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sync.date-path-pattern (SYNC_DATE_PATH_PATTERN)")
                .hasMessageContaining("not a valid date layout");
    }

    @Test
    void layoutNamingTimeFieldIsRefused() {
        // Parses fine; throws only when a LocalDate is formatted, i.e. on the first sync run.
        assertThatThrownBy(() -> resolver("logs", "yyyy/MM/dd/HH"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sync.date-path-pattern (SYNC_DATE_PATH_PATTERN)")
                .hasMessageContaining("cannot render a date");
    }

    @Test
    void layoutOmittingTheDayIsRefused() {
        assertThatThrownBy(() -> resolver("logs", "yyyy/MM"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not distinguish dates");
    }

    @Test
    void layoutOmittingTheYearIsRefused() {
        // The case a probe set varying several fields at once would wrongly admit: distinct within one
        // year, collides across years.
        assertThatThrownBy(() -> resolver("logs", "MM/dd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not distinguish dates");
    }

    @Test
    void layoutCarryingWeekdayInPlaceOfDayIsRefused() {
        // Distinct for four dates chosen one field apart, yet four-way ambiguous within every month: only
        // sweeping a real range of dates catches a field that is present but too coarse.
        assertThatThrownBy(() -> resolver("logs", "yyyy/MM/E"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not distinguish dates");
    }

    @Test
    void layoutCarryingQuarterInPlaceOfMonthIsRefused() {
        assertThatThrownBy(() -> resolver("logs", "yyyy/QQQ/dd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not distinguish dates");
    }

    @Test
    void refusalNamesTheCollidingDates() {
        assertThatThrownBy(() -> resolver("logs", "MM/dd"))
                .hasMessageContaining("renders '01/01' for both 2024-01-01 and 2025-01-01");
    }

    @Test
    void purelyLiteralLayoutIsRefused() {
        assertThatThrownBy(() -> resolver("logs", "'current'"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not distinguish dates");
    }

    @Test
    void blankLayoutWithBlankHeadIsRefused() {
        // Blank now means "no date directory", so the file name's leading text is what has to carry the
        // date; a blank one leaves every day on the base path and the same sweep catches it on day two.
        assertThatThrownBy(() -> resolver("logs", "   ", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sync.file-name-prefix-pattern (SYNC_FILE_NAME_PREFIX_PATTERN)")
                .hasMessageContaining("does not distinguish dates")
                .hasMessageContaining("no date directory");
    }

    @Test
    void nullLayoutWithBlankHeadIsRefused() {
        // Unset and blank are the same configuration, as they already are for the head.
        assertThatThrownBy(() -> resolver("logs", null, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not distinguish dates");
    }

    // --- the file-name head (sync.file-name-prefix-pattern) ---

    @Test
    void blankHeadPlacesTheTimestampAfterTheDateDirectory() {
        // A flat, epoch-leading shape: your_dir/2026-06-24/1782297030-<uuid>.log.gz, with no `date=` before the epoch.
        BlobPrefixResolver bare = resolver("your_dir", "yyyy-MM-dd", "");
        assertThat(bare.bandPrefixes(Instant.ofEpochSecond(1782297030), Instant.ofEpochSecond(1782297600), 1000))
                .containsExactly("your_dir/2026-06-24/1782297");
        // The head narrows nothing on its own, so a blank one leaves the whole-day prefix untouched.
        assertThat(bare.dayPrefix(LocalDate.of(2026, 6, 18))).isEqualTo("your_dir/2026-06-18/");
    }

    @Test
    void blankHeadStillYieldsOnePrefixPerDayAcrossMidnight() {
        // Band 1782345 straddles midnight; without a head the two prefixes differ only by the directory.
        List<String> prefixes = resolver("your_dir", "yyyy-MM-dd", "").bandPrefixes(
                Instant.ofEpochSecond(1782345500), Instant.ofEpochSecond(1782345800), 1000);
        assertThat(prefixes).containsExactlyInAnyOrder(
                "your_dir/2026-06-24/1782345",
                "your_dir/2026-06-25/1782345");
    }

    @Test
    void literalHeadIsUsedVerbatim() {
        // A head naming no date field is legitimate: the date directory in front of it pins the day.
        assertThat(resolver("logs", DEFAULT_PATTERN, "'usage-'").bandPrefixes(
                Instant.ofEpochSecond(1782297030), Instant.ofEpochSecond(1782297600), 1000))
                .containsExactly("logs/2026/06/24/usage-1782297");
    }

    @Test
    void headRenderingLeadingSlashDoesNotDoubleUp() {
        // dayPrefix already supplies the separator, so a head written with one must not add a second.
        assertThat(resolver("logs", DEFAULT_PATTERN, "'/date='yyyy-MM-dd").bandPrefixes(
                Instant.ofEpochSecond(1782297030), Instant.ofEpochSecond(1782297600), 1000))
                .containsExactly("logs/2026/06/24/date=2026-06-241782297");
    }

    @Test
    void headEndingInSlashIsKept() {
        // The opposite of the date segment: a producer nesting one level deeper ends its head with '/'.
        assertThat(resolver("logs", DEFAULT_PATTERN, "'pod/'").bandPrefixes(
                Instant.ofEpochSecond(1782297030), Instant.ofEpochSecond(1782297600), 1000))
                .containsExactly("logs/2026/06/24/pod/1782297");
    }

    @Test
    void headDoesNotAffectTheWholeDayPrefix() {
        // Scheduled run, backfill and the poller's day fallback list by directory only.
        assertThat(resolver("logs", DEFAULT_PATTERN, "'anything'").dayPrefix(LocalDate.of(2026, 6, 18)))
                .isEqualTo("logs/2026/06/18/");
    }

    @Test
    void unparsableHeadIsRefused() {
        assertThatThrownBy(() -> resolver("logs", DEFAULT_PATTERN, "'date="))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sync.file-name-prefix-pattern (SYNC_FILE_NAME_PREFIX_PATTERN)")
                .hasMessageContaining("not a valid date layout");
    }

    @Test
    void headNamingTimeFieldIsRefused() {
        // Parses fine; would throw only when a band prefix is first built, i.e. on the first poll cycle.
        assertThatThrownBy(() -> resolver("logs", DEFAULT_PATTERN, "'date='yyyy-MM-dd'T'HH"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sync.file-name-prefix-pattern (SYNC_FILE_NAME_PREFIX_PATTERN)")
                .hasMessageContaining("cannot render a date");
    }

    @Test
    void blankHeadIsAccepted() {
        // The asymmetry with the date directory, where blank aborts startup: a blank head widens nothing,
        // because the date directory still bounds every listing.
        assertThat(resolver("logs", DEFAULT_PATTERN, "").dayPrefix(LocalDate.of(2026, 6, 18)))
                .isEqualTo("logs/2026/06/18/");
        assertThat(resolver("logs", DEFAULT_PATTERN, "   ").dayPrefix(LocalDate.of(2026, 6, 18)))
                .isEqualTo("logs/2026/06/18/");
    }

    @Test
    void purelyLiteralHeadIsAccepted() {
        // Refused for the date directory (it collapses every day onto one prefix), fine here.
        assertThat(resolver("logs", DEFAULT_PATTERN, "'current-'").bandPrefixes(
                Instant.ofEpochSecond(1782297030), Instant.ofEpochSecond(1782297600), 1000))
                .containsExactly("logs/2026/06/24/current-1782297");
    }

    // --- the band-shape report (checkBandShape) ---

    private static final LocalDate DAY = LocalDate.of(2026, 6, 24);
    private static final String MISMATCH_METER = "sync.blobs.band.shape.mismatch";

    @Test
    void bandShapeCheckPassesForMatchingKeys() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        resolver("logs", DEFAULT_PATTERN, DEFAULT_HEAD, registry).checkBandShape(DAY, List.of(
                "logs/2026/06/24/date=2026-06-2417822970301782297630-11111111-2222-3333-4444-555555555555.log.gz",
                "logs/2026/06/24/date=2026-06-2417822976301782298230-66666666-7777-8888-9999-000000000000.log.gz"));
        assertThat(registry.counter(MISMATCH_METER).count()).isZero();
    }

    @Test
    void bandShapeCheckCountsNonMatchingKeys() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        resolver("logs", DEFAULT_PATTERN, DEFAULT_HEAD, registry).checkBandShape(DAY, List.of(
                "logs/2026/06/24/date=2026-06-2417822970301782297630-11111111-2222-3333-4444-555555555555.log.gz",
                // An epoch-leading key under the default `date=` head: band listing would never resolve a prefix matching it.
                "logs/2026/06/24/1782297030-66666666-7777-8888-9999-000000000000.log.gz",
                // A nested key: also unreachable by a band prefix, and worth counting for the same reason.
                "logs/2026/06/24/pod-1/1782297030-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.log.gz"));
        assertThat(registry.counter(MISMATCH_METER).count()).isEqualTo(2);
    }

    @Test
    void bandShapeCheckCountsEveryRun() {
        // The warning is logged once per process; the counter must keep moving, or a condition that
        // persists would read as resolved to monitoring.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BlobPrefixResolver defaultHead = resolver("logs", DEFAULT_PATTERN, DEFAULT_HEAD, registry);
        List<String> keys = List.of("logs/2026/06/24/1782297030-11111111-2222-3333-4444-555555555555.log.gz");
        defaultHead.checkBandShape(DAY, keys);
        defaultHead.checkBandShape(DAY, keys);
        assertThat(registry.counter(MISMATCH_METER).count()).isEqualTo(2);
    }

    @Test
    void bandShapeCheckPassesUnderBlankHead() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BlobPrefixResolver blankHead = resolver("your_dir", "yyyy-MM-dd", "", registry);
        blankHead.checkBandShape(DAY, List.of(
                "your_dir/2026-06-24/1782297030-11111111-2222-3333-4444-555555555555.log.gz"));
        assertThat(registry.counter(MISMATCH_METER).count()).isZero();
        // The mirror image of the default-head misconfiguration: a `date=` head where none is configured.
        blankHead.checkBandShape(DAY, List.of(
                "your_dir/2026-06-24/date=2026-06-241782297030-66666666-7777-8888-9999-000000000000.log.gz"));
        assertThat(registry.counter(MISMATCH_METER).count()).isEqualTo(1);
    }

    @Test
    void bandShapeCheckIgnoresAnEmptyListing() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        resolver("logs", DEFAULT_PATTERN, DEFAULT_HEAD, registry).checkBandShape(DAY, List.of());
        assertThat(registry.counter(MISMATCH_METER).count()).isZero();
    }

    // --- no date directory at all (blank sync.date-path-pattern) ---

    @Test
    void flatLayoutAbsorbsTheHeadIntoTheWholeDayPrefix() {
        // A flat `date=` layout: logs/date=2025-10-22<epoch>-<uuid>.log.gz, with no directory between the base
        // path and the file name. The prefix has to end mid-file-name, or it would not name a day at all.
        assertThat(resolver("logs", "", DEFAULT_HEAD).dayPrefix(LocalDate.of(2025, 10, 22)))
                .isEqualTo("logs/date=2025-10-22");
        // Unset reads as blank, and the base path is still optional.
        assertThat(resolver("logs", null, DEFAULT_HEAD).dayPrefix(LocalDate.of(2025, 10, 22)))
                .isEqualTo("logs/date=2025-10-22");
        assertThat(resolver("", "", DEFAULT_HEAD).dayPrefix(LocalDate.of(2025, 10, 22)))
                .isEqualTo("date=2025-10-22");
    }

    @Test
    void flatLayoutBandPrefixesDoNotRepeatTheHead() {
        // The whole-day prefix already carries the head, so band prefixes are built from the date
        // directory (here: nothing) rather than from it, and the digits still follow the head exactly once.
        BlobPrefixResolver flat = resolver("logs", "", DEFAULT_HEAD);
        assertThat(flat.bandPrefixes(Instant.ofEpochSecond(1782297030), Instant.ofEpochSecond(1782297600), 1000))
                .containsExactly("logs/date=2026-06-241782297");
        assertThat(flat.bandPrefixes(Instant.ofEpochSecond(1782345500), Instant.ofEpochSecond(1782345800), 1000))
                .containsExactlyInAnyOrder(
                        "logs/date=2026-06-241782345",
                        "logs/date=2026-06-251782345");
    }

    @Test
    void flatLayoutBandShapeCheckAcceptsTheLiveKeys() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BlobPrefixResolver flat = resolver("logs", "", DEFAULT_HEAD, registry);
        // Exactly the shape the bucket holds: head, then the ten-digit epoch, then the uuid.
        flat.checkBandShape(DAY, List.of(
                "logs/date=2026-06-241782297030-11111111-2222-3333-4444-555555555555.log.gz",
                "logs/date=2026-06-241782297630-66666666-7777-8888-9999-000000000000.log.gz"));
        assertThat(registry.counter(MISMATCH_METER).count()).isZero();
        // A key that kept the date directory the layout says is absent: unreachable by any band prefix.
        flat.checkBandShape(DAY, List.of(
                "logs/date=2026-06-24/1782297030-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.log.gz"));
        assertThat(registry.counter(MISMATCH_METER).count()).isEqualTo(1);
    }

    @Test
    void flatLayoutWithDatelessHeadIsRefused() {
        // Nothing left carries the date: every day would list the whole base path, which is the outcome
        // refusing a blank date layout used to prevent outright.
        assertThatThrownBy(() -> resolver("logs", "", "'usage-'"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sync.file-name-prefix-pattern (SYNC_FILE_NAME_PREFIX_PATTERN)")
                .hasMessageContaining("does not distinguish dates");
    }

    @Test
    void flatLayoutWithVariableWidthHeadIsRefused() {
        // Distinct per day, yet 'd=2025-1-1' also matches every key of 2025-01-10..19, so a whole-day
        // listing would pull ten other days. The trailing separator is what rules this out elsewhere, and
        // a prefix that ends mid-file-name has given it up.
        assertThatThrownBy(() -> resolver("logs", "", "'d='yyyy-M-d"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sync.file-name-prefix-pattern (SYNC_FILE_NAME_PREFIX_PATTERN)")
                .hasMessageContaining("prefix of another's")
                // The text the operator configured, not the base path it was concatenated into.
                .hasMessageContaining("'d=2024-1-1' for 2024-01-01")
                .hasMessageNotContaining("'logs/d=2024-1-1'");
    }
}
