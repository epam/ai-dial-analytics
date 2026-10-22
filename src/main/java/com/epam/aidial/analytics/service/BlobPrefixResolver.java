package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.configuration.SyncProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Builds the blob key prefixes the ingestion paths list under, and the single owner of the configured
 * key layout — the date directory ({@code sync.date-path-pattern}) and the file name's leading text
 * ({@code sync.file-name-prefix-pattern}). Every path resolves through this component — the scheduled
 * reconciliation run and the operator backfill via {@link #dayPrefix(LocalDate)}, the poller via
 * {@code dayPrefix} in {@code day} listing mode and {@link #bandPrefixes} in {@code band} mode — so a
 * deployment whose producer names blobs differently becomes ingestible by configuration alone, and no
 * path can be left behind on the old layout.
 *
 * <p>A key is {@code <path-prefix>/<date-path>/<file name>}. {@code <date-path>} is the target UTC date
 * rendered through the configured date layout: {@code yyyy/MM/dd} (the default) gives
 * {@code logs/2026/06/24/…}, {@code yyyy-MM-dd} gives {@code your_dir/2026-06-24/…}. The file name is
 * the producer's, but its <em>leading text</em> — everything before the embedded timestamp — is
 * configured too, because it also differs between producers: {@code 'date='yyyy-MM-dd} (the default)
 * gives {@code date=2026-06-24…}, and a blank layout means the timestamp begins the file name
 * ({@code your_dir/2026-06-24/1782297…}). An unusable layout fails the context at construction rather
 * than quietly listing a prefix that matches nothing — see {@link #validatedDatePath(String, String)}
 * and {@link #validatedFileNamePrefix(String)}.
 *
 * <p>Some producers write no date directory at all, keeping the date in the file name instead
 * ({@code logs/date=2026-06-24<epoch>-<uuid>.log.gz}); a blank date layout configures exactly that. The
 * whole-day prefix then <em>absorbs</em> the file name's leading text and ends mid-file-name, because
 * the alternative — dropping the segment and its separator — would leave every whole-day listing at the
 * base path, independent of the date, and each run would re-ingest the whole container. That shifts the
 * burden of pinning the day onto the file-name layout, so it is the whole-day prefix, not the date
 * segment, that startup validation sweeps.
 *
 * <p><strong>Band listing</strong> narrows a poll cycle to a "recent time slice" so the store filters
 * server-side instead of returning the whole day. It works because the blob key embeds a fixed-width
 * epoch-seconds timestamp at a fixed offset in the file name, e.g.
 * {@code logs/2026/06/24/date=2026-06-24<startEpoch><endEpoch>-<uuid>.log.gz}. A text prefix of a
 * fixed-width decimal number selects a contiguous numeric range, so a prefix that fixes the leading
 * digits of that timestamp is a time-range filter. A "band" here is one
 * {@code floor(epoch / bandWidth)} bucket; with a power-of-ten width its leading digits equal the band
 * index, which is used verbatim as the key prefix. The timestamp itself stays outside configuration —
 * its unit, width and position are what make the prefix a numeric range at all, so
 * {@link #checkBandShape} reports a file name that does not carry it where the configuration says it
 * should, instead of leaving the poller to list nothing and report success.
 *
 * <p>The poller asks for the slice covering start-epochs in {@code [lo, hi]} and lists every band the
 * range touches; a band straddling UTC midnight yields a prefix for each day it touches. Selection stays
 * exact via the caller's in-memory last-modified watermark check over the (now small) candidate set.
 */
@Component
@Slf4j
class BlobPrefixResolver {

    /** Upper bound on bands enumerated per call; guards against a tiny width over a wide range (OOM/hang). */
    private static final long MAX_BANDS = 100_000;

    /**
     * The widest band {@link #bandPrefixes} accepts. A band is emitted once per date directory it touches,
     * and that enumeration covers only the day the band starts on and the day it ends on — complete
     * exactly while a band is no wider than a day. A wider one would silently skip the directories in
     * between, which is the "lists a prefix matching nothing and reports success" outcome this component
     * exists to rule out. Nothing above the bound is useful anyway: a band at least a day wide lists more
     * than the {@code day} mode it exists to undercut.
     */
    private static final long MAX_BAND_WIDTH_SECONDS = 86_400;

    /**
     * Band width for bounded ({@code band}) listing, in seconds, and the single owner of the value — the
     * poller lists with it and the startup example renders it, so an operator comparing that example
     * against a real key cannot be shown different leading digits than the poller asks for. MUST be a
     * power of ten: a decimal key prefix can only express 10<sup>k</sup>-second ranges. 1000s (≈ 16.7 min)
     * keeps each listed slice tight <em>independently of</em> {@code poll.finalization-lag} — as many
     * adjacent bands are listed as the lookback window needs (a few per cycle at the default lag), so a
     * larger lag costs a couple more cheap list calls rather than a wider, and far heavier, slice.
     */
    static final long BAND_WIDTH_SECONDS = 1000;

    /**
     * Named in every validation failure, because whoever reads it is holding a deployment manifest rather
     * than {@code application.yml}.
     */
    private static final String DATE_PATH_SETTING = "sync.date-path-pattern (SYNC_DATE_PATH_PATTERN)";
    private static final String FILE_NAME_SETTING = "sync.file-name-prefix-pattern (SYNC_FILE_NAME_PREFIX_PATTERN)";

    /**
     * The window the "distinguishes any two dates" check sweeps: one full leap cycle, so it spans several
     * years, every month, and a 29 February. Sweeping beats probing a handful of dates — probes chosen to
     * isolate the year, month and day catch a layout that <em>omits</em> a field, but admit one that
     * merely weakens it ({@code yyyy/MM/E} renders a weekday where a day-of-month belongs and collides
     * four times a month; {@code yyyy/QQQ/dd} collides across the months of a quarter). Every such layout
     * collapses distinct days onto one prefix, which is not an error at run time — it is a listing that
     * silently covers far more than its target date. The window is deliberately not centuries wide: a
     * two-digit year is a working layout, not a defect to refuse.
     */
    private static final LocalDate SWEEP_FROM = LocalDate.of(2024, 1, 1);
    private static final LocalDate SWEEP_TO = LocalDate.of(2027, 12, 31);

    /** Any date will do: the check is that the layout can render <em>a</em> calendar date at all. */
    private static final LocalDate FORMAT_PROBE = LocalDate.of(2026, 6, 24);

    private final String basePrefix;
    /** {@code null} when the configured layout is blank, i.e. the keys carry no date directory at all. */
    private final DateTimeFormatter datePath;
    /** {@code null} when the configured layout is blank, i.e. the embedded timestamp begins the file name. */
    private final DateTimeFormatter fileNamePrefix;
    private final Counter bandShapeMismatch;
    /** Holds the once-per-process warning; the counter still records every run. */
    private final AtomicBoolean bandShapeWarned = new AtomicBoolean();

    BlobPrefixResolver(SyncProperties properties, MeterRegistry meterRegistry) {
        // Order is load-bearing: a blank date layout makes the whole-day prefix run into the file name, so
        // the sweep inside validatedDatePath renders that head against this base path and both must already
        // be resolved when it runs. (That is also why it is no longer static.)
        this.fileNamePrefix = validatedFileNamePrefix(properties.fileNamePrefixPattern());
        this.basePrefix = basePrefix(properties.pathPrefix());
        this.datePath = validatedDatePath(properties.datePathPattern(), properties.fileNamePrefixPattern());
        this.bandShapeMismatch = meterRegistry.counter("sync.blobs.band.shape.mismatch");
        // Logged once, with real dates rendered rather than the patterns interpolated, so an operator sees
        // the keys that will actually be listed — a quoted literal, a trailing slash or a blank head does not
        // read back as itself. Confirming both layouts costs a restart, not a wait for the next cron cycle.
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        log.info("Blob discovery prefix template: {}, e.g. {}; band prefix example: {}",
                prefixTemplate(properties), dayPrefix(today), exampleBandPrefix(today));
    }

    /**
     * The configured layout echoed back in the startup line, in the shape the prefixes actually take: the
     * date directory and its separator normally, and the file name's leading text — which the whole-day
     * prefix absorbs — when no date directory is configured, where {@code <>/} would read as a lost segment.
     */
    private String prefixTemplate(SyncProperties properties) {
        return datePath == null
                ? basePrefix + "<" + StringUtils.trimToEmpty(properties.fileNamePrefixPattern()) + ">"
                : basePrefix + "<" + StringUtils.trimToEmpty(properties.datePathPattern()) + ">/";
    }

    /**
     * The prefix covering every blob of one UTC day — the whole-day listing used by the scheduled
     * reconciliation run, the operator backfill, and the poller's {@code day} fallback. Unaffected by the
     * file-name layout, which only ever narrows within a day — <em>except</em> where no date directory is
     * configured, in which case that layout is the only thing carrying the date and the whole-day prefix
     * absorbs it ({@code logs/date=2026-06-18}).
     *
     * @param day the UTC date to list
     * @return the key prefix: with a trailing slash where a date directory exists, so it cannot match a
     *         sibling directory by name, and otherwise ending in the rendered file-name head, which startup
     *         validation has already established both pins the day and cannot reach into another day's keys
     */
    String dayPrefix(LocalDate day) {
        return dayPrefix(datePath, day);
    }

    /**
     * {@link #dayPrefix(LocalDate)} against an arbitrary date formatter, so the startup sweep can render
     * candidate prefixes before the validated formatter reaches the field.
     */
    private String dayPrefix(DateTimeFormatter formatter, LocalDate day) {
        return formatter == null ? basePrefix + fileNameHead(day) : dateDirPrefix(formatter, day);
    }

    /**
     * Everything a key carries before its file name: the base path plus the date directory and its
     * separator, or just the base path when no date directory is configured. Kept apart from
     * {@link #dayPrefix(LocalDate)}, which it equals only while a date directory exists, because the
     * file-name portion is built on top of it — over a whole-day prefix that has already absorbed the head,
     * every band prefix and the band-shape expectation would render that head twice.
     */
    private String dateDirPrefix(DateTimeFormatter formatter, LocalDate day) {
        return formatter == null ? basePrefix : basePrefix + dateSegment(formatter, day) + "/";
    }

    /**
     * The key prefixes covering every blob whose embedded start epoch falls in {@code [lo, hi]}. One prefix
     * is emitted per band the range touches, so a wider range simply yields more (cheap) prefixes rather
     * than a coarser slice; band width is therefore chosen for tightness, not to span the range.
     *
     * @param lo               inclusive lower bound on the start epoch (cutoff less the finalization lag)
     * @param hi               inclusive upper bound on the start epoch (typically now)
     * @param bandWidthSeconds band width in seconds; MUST be a power of ten, because the band's leading
     *                         digits are used verbatim as the key prefix, and MUST NOT exceed one day, so
     *                         a band touches at most the two date directories enumerated below
     * @return distinct prefixes, in ascending time order (empty if {@code hi} precedes {@code lo})
     */
    List<String> bandPrefixes(Instant lo, Instant hi, long bandWidthSeconds) {
        if (!isPowerOfTen(bandWidthSeconds)) {
            throw new IllegalArgumentException("bandWidthSeconds must be a positive power of ten "
                    + "(1, 10, 100, ...) so the band index equals the key's leading digits; got "
                    + bandWidthSeconds);
        }
        if (bandWidthSeconds > MAX_BAND_WIDTH_SECONDS) {
            throw new IllegalArgumentException("bandWidthSeconds must not exceed one day ("
                    + MAX_BAND_WIDTH_SECONDS + "s), so a band spans at most two date directories and every "
                    + "one it touches is listed; got " + bandWidthSeconds + ". A band that wide lists more "
                    + "than the whole-day (day) listing mode it exists to undercut");
        }
        long loEpoch = lo.getEpochSecond();
        long hiEpoch = hi.getEpochSecond();
        if (hiEpoch < loEpoch) {
            return List.of();
        }
        long firstBand = Math.floorDiv(loEpoch, bandWidthSeconds);
        long lastBand = Math.floorDiv(hiEpoch, bandWidthSeconds);
        long bandCount = lastBand - firstBand + 1;
        if (bandCount > MAX_BANDS) {
            throw new IllegalArgumentException("band count " + bandCount + " exceeds the cap of " + MAX_BANDS
                    + " for band width " + bandWidthSeconds + "s over [" + lo + ", " + hi
                    + "]; widen the poll band width or narrow the range");
        }
        Set<String> prefixes = new LinkedHashSet<>();
        for (long band = firstBand; band <= lastBand; band++) {
            long bandStart = band * bandWidthSeconds;
            long bandEnd = bandStart + bandWidthSeconds - 1;
            // At most two date directories, guaranteed by the band-width bound checked above: anything
            // wider could straddle a third, whose blobs no emitted prefix would then reach.
            LocalDate startDay = epochDay(bandStart);
            LocalDate endDay = epochDay(bandEnd);
            // floor(epoch / 10^k) == the epoch's leading digits, which is exactly the key digits shared
            // by every blob in this band, regardless of which day the band falls on.
            String digits = Long.toString(band);
            prefixes.add(bandPrefix(startDay, digits));
            if (!endDay.equals(startDay)) {
                prefixes.add(bandPrefix(endDay, digits));
            }
        }
        return List.copyOf(prefixes);
    }

    /**
     * Reports listed keys whose file name the configured layout cannot match, so that a head which is
     * valid but wrong surfaces here rather than as {@code band}-mode poll cycles that discover nothing and
     * report success. Band listing cannot detect its own misconfiguration — a wrong prefix returns an
     * empty listing, which is what an idle environment also returns — but the whole-day paths hold the
     * keys already, so this is an assertion over those, not a probe of the container: it performs no
     * listing and changes nothing about what is ingested.
     *
     * <p><strong>Weaker where no date directory is configured.</strong> There the whole-day prefix already
     * carries the head, so a key that this listing returned cannot fail the head comparison — only the
     * "a digit follows" test still bites. A <em>wrong</em> head is then undetectable here by construction:
     * it makes the whole-day listing itself return nothing, which is indistinguishable from an idle day,
     * and whole-day ingestion is no longer the unaffected fallback the warning names in the other mode.
     * The startup line, which renders the prefix an operator can hold against a real key, is what covers
     * that case instead.
     *
     * <p>The counter records every call, since a condition that stops incrementing reads as resolved to
     * monitoring; the warning is logged at most once per process, since one that repeats every run is one
     * people filter out.
     *
     * @param day  the UTC date whose whole-day prefix produced {@code keys}
     * @param keys the keys that listing returned, in the form the store reported them
     */
    void checkBandShape(LocalDate day, List<String> keys) {
        String expected = dateDirPrefix(datePath, day) + fileNameHead(day);
        long mismatches = 0;
        String offender = null;
        for (String key : keys) {
            if (!startsWithBandShape(key, expected)) {
                mismatches++;
                if (offender == null) {
                    offender = key;
                }
            }
        }
        if (mismatches == 0) {
            return;
        }
        bandShapeMismatch.increment(mismatches);
        if (bandShapeWarned.compareAndSet(false, true)) {
            log.warn("{} of {} listed blob(s) do not match the band-listing key shape '{}<digits>': e.g. {}. "
                            + "Bounded (band) polling cannot discover blobs like these — correct {}{}. This warning is "
                            + "logged once per process while the sync.blobs.band.shape.mismatch counter records every run.",
                    mismatches, keys.size(), expected, offender, FILE_NAME_SETTING, bandShapeRemedy());
        }
    }

    /**
     * Validates the configured date-directory layout, refusing anything that cannot work rather than
     * letting it surface later as successful zero-row runs. The layout must parse; it must actually
     * <em>format</em> a date (a pattern naming a time field such as {@code HH} parses fine and throws only
     * at format time); and the whole-day prefix it yields must, across {@link #SWEEP_FROM}..
     * {@link #SWEEP_TO}, both be distinct per day and be free of every other day's prefix.
     *
     * <p>Blank is <em>accepted</em> and means "no date directory" — see the class javadoc for why the
     * whole-day prefix then absorbs the file name's leading text instead of simply losing a segment. That
     * is also why the sweep runs over the rendered {@link #dayPrefix(LocalDate)} rather than over the date
     * segment alone: it leaves {@code yyyy/MM} refused as before (the head is no part of the prefix while a
     * date directory exists) and admits a blank layout exactly when the head pins the day. A blank or
     * dateless head under a blank layout needs no refusal of its own — it collides with itself on the
     * second day of the sweep.
     *
     * <p>Two collapses are possible, hence two refusals. Two days may render the <em>same</em> prefix, so
     * every listing covers more than its target date. Or one day's prefix may be a proper prefix of
     * another's, which the trailing separator rules out everywhere else and which only a blank layout can
     * therefore reach: {@code 'd='yyyy-M-d} renders {@code d=2025-1-1}, and listing under it also returns
     * 2025-01-10..19. Distinctness does not catch the second — those strings differ.
     *
     * @param pattern     the configured layout; blank or {@code null} means no date directory
     * @param headPattern the configured file-name layout, named in a refusal when a blank date layout has
     *                    left it as the only thing carrying the date
     * @return a formatter for the layout, or {@code null} if it is blank
     * @throws IllegalArgumentException if the layout is unusable; Spring surfaces this wrapped in a
     *                                 {@code BeanCreationException}, so the message stands alone
     */
    private DateTimeFormatter validatedDatePath(String pattern, String headPattern) {
        DateTimeFormatter formatter = StringUtils.isBlank(pattern) ? null : parsed(pattern, DATE_PATH_SETTING);
        // Both refusals blame whichever setting the operator has to edit: with no date directory that is the
        // head, since the date layout they would be pointed at is the one they deliberately left blank.
        String setting = formatter == null ? FILE_NAME_SETTING : DATE_PATH_SETTING;
        String blamed = formatter == null ? StringUtils.trimToEmpty(headPattern) : pattern;
        Map<String, LocalDate> renderedBy = new HashMap<>();
        for (LocalDate day = SWEEP_FROM; !day.isAfter(SWEEP_TO); day = day.plusDays(1)) {
            String prefix;
            try {
                prefix = dayPrefix(formatter, day);
            } catch (DateTimeException ex) {
                // Normally only the date layout can reach this. In blank mode the head is what renders, and
                // validatedFileNamePrefix probed only one date, so a head that renders that date and throws
                // for another lands here too — blame whichever setting actually produced the text.
                throw cannotRenderDate(blamed, setting, ex);
            }
            LocalDate clash = renderedBy.putIfAbsent(prefix, day);
            if (clash != null) {
                // The rendered segment, or the head standing in for it, rather than the whole prefix: the
                // operator recognizes what they configured, not the base path prepended to it.
                String rendered = formatter == null ? fileNameHead(day) : dateSegment(formatter, day);
                throw new IllegalArgumentException(setting + " does not distinguish dates: '" + blamed + "' renders '"
                        + rendered + "' for both " + clash + " and " + day + ", so distinct days would collapse onto one "
                        + "prefix and every listing would cover more than its target date." + remedy(formatter));
            }
        }
        // Sorted, the entries carrying a given prefix form one block headed by that prefix itself, so a
        // violation always sits between neighbours and the pairs in between need no testing.
        List<String> sorted = new ArrayList<>(renderedBy.keySet());
        Collections.sort(sorted);
        for (int i = 1; i < sorted.size(); i++) {
            String shorter = sorted.get(i - 1);
            String longer = sorted.get(i);
            if (longer.startsWith(shorter)) {
                // Quoted without the base path, as the distinctness refusal above is: the operator recognizes
                // the text their own setting renders, not the prefix it was concatenated into.
                throw new IllegalArgumentException(setting + " renders one day's text as a prefix of another's: '"
                        + blamed + "' gives '" + withoutBasePrefix(shorter) + "' for " + renderedBy.get(shorter)
                        + ", which also matches every key of " + renderedBy.get(longer) + " ('" + withoutBasePrefix(longer)
                        + "'), so a whole-day listing would ingest days it was never asked for. Use a fixed-width layout, "
                        + "zero-padded throughout ('date='yyyy-MM-dd), so no day's text can extend into another's."
                        + remedy(formatter));
            }
        }
        return formatter;
    }

    /** Drops the configured base path from a rendered prefix, leaving only the text the layout produced. */
    private String withoutBasePrefix(String prefix) {
        return prefix.startsWith(basePrefix) ? prefix.substring(basePrefix.length()) : prefix;
    }

    /** The closing advice shared by both layout refusals; which setting can fix it depends on the mode. */
    private static String remedy(DateTimeFormatter datePathFormatter) {
        return datePathFormatter == null
                ? " With " + DATE_PATH_SETTING + " blank there is no date directory, so the file name's leading text is the only thing "
                        + "pinning the day: give it a full date layout such as 'date='yyyy-MM-dd, or configure a date directory."
                : " Include the year, the month and the day of the month.";
    }

    /**
     * Validates the configured file-name layout — the text between the date directory and the embedded
     * timestamp. Two checks, not the date directory's four: it must parse, and it must be able to render a
     * calendar date (a layout naming a time field parses fine and would throw on the first poll cycle).
     *
     * <p>Deliberately <em>no</em> distinctness sweep and <em>no</em> blank refusal, which is where this
     * differs from {@link #validatedDatePath(String, String)}: a layout rendering the same text for every
     * date — a bare literal, or nothing at all — is a legitimate producer shape, because the date directory
     * in front of it already pins the day. Applying the date directory's checks here would refuse exactly
     * the value a producer that writes {@code <epoch>-<uuid>.log.gz} needs. Where there <em>is</em> no date
     * directory the day has to be pinned here after all, but that is a property of the pair rather than of
     * this layout alone, so the sweep over the whole-day prefix owns it and it is never duplicated here.
     *
     * @param pattern the configured layout; blank means the timestamp begins the file name
     * @return a formatter for it, or {@code null} if the layout is blank
     * @throws IllegalArgumentException if the layout is unusable
     */
    private static DateTimeFormatter validatedFileNamePrefix(String pattern) {
        if (StringUtils.isBlank(pattern)) {
            return null;
        }
        DateTimeFormatter formatter = parsed(pattern, FILE_NAME_SETTING);
        try {
            formatter.format(FORMAT_PROBE);
        } catch (DateTimeException ex) {
            throw cannotRenderDate(pattern, FILE_NAME_SETTING, ex);
        }
        return formatter;
    }

    private static DateTimeFormatter parsed(String pattern, String setting) {
        try {
            // Locale.ROOT is explicit so a layout using textual fields (MMM) renders the same on every host.
            return DateTimeFormatter.ofPattern(pattern, Locale.ROOT);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(setting + " is not a valid date layout: '" + pattern + "' (" + ex.getMessage() + ")", ex);
        }
    }

    private static IllegalArgumentException cannotRenderDate(String pattern, String setting, DateTimeException cause) {
        return new IllegalArgumentException(setting + " cannot render a date: '" + pattern
                + "' names a field a calendar date cannot supply (" + cause.getMessage() + ")", cause);
    }

    private static boolean isPowerOfTen(long value) {
        if (value <= 0) {
            return false;
        }
        long remaining = value;
        while (remaining % 10 == 0) {
            remaining /= 10;
        }
        return remaining == 1;
    }

    private static LocalDate epochDay(long epochSecond) {
        return Instant.ofEpochSecond(epochSecond).atZone(ZoneOffset.UTC).toLocalDate();
    }

    /**
     * The half of the band-shape warning that only holds under a date directory: there, falling back to
     * whole-day listing sidesteps the file-name layout entirely. With no date directory the whole-day
     * prefix is built from that same layout, so the fallback does not exist and saying otherwise would send
     * an operator to a setting that cannot help.
     */
    private String bandShapeRemedy() {
        return datePath == null
                ? ". With " + DATE_PATH_SETTING + " blank there is no whole-day fallback: the file name's leading text is "
                        + "what every discovery path lists under, so these blobs are out of reach on all of them"
                : " or set POLL_LISTING_MODE=day — whole-day ingestion (this run and the operator backfill) is unaffected";
    }

    /** Whether {@code key} continues past {@code expected} with a digit, i.e. carries the embedded timestamp. */
    private static boolean startsWithBandShape(String key, String expected) {
        if (!key.startsWith(expected) || key.length() == expected.length()) {
            return false;
        }
        char next = key.charAt(expected.length());
        // Explicitly ASCII: Character.isDigit would also admit digits no producer writes in a key.
        return next >= '0' && next <= '9';
    }

    private String bandPrefix(LocalDate day, String startEpochDigits) {
        return dateDirPrefix(datePath, day) + fileNameHead(day) + startEpochDigits;
    }

    private String exampleBandPrefix(LocalDate day) {
        long band = Math.floorDiv(Instant.now().getEpochSecond(), BAND_WIDTH_SECONDS);
        return bandPrefix(day, Long.toString(band));
    }

    /**
     * The file name's leading text for a day, empty when no layout is configured. Leading slashes are
     * stripped from the <em>rendered</em> text so a layout written {@code /date=…} cannot double the
     * separator {@link #dateDirPrefix} already supplies; a trailing one is kept, since a producer nesting one
     * level deeper legitimately ends its head with a separator. Stripping the pattern instead would
     * corrupt a quoted literal, as it would for the date segment.
     */
    private String fileNameHead(LocalDate day) {
        return fileNamePrefix == null ? "" : StringUtils.stripStart(fileNamePrefix.format(day), "/");
    }

    /**
     * The date directory for a day. Slashes are stripped from the <em>rendered</em> segment rather than
     * from the pattern, so a layout written {@code yyyy-MM-dd/} cannot produce a double slash while a
     * quoted literal containing one ({@code 'logs/'yyyy/MM/dd}) survives intact.
     */
    private static String dateSegment(DateTimeFormatter formatter, LocalDate day) {
        return StringUtils.strip(formatter.format(day), "/");
    }

    /**
     * The configured base path, normalized once into the form every prefix starts with: empty, or ending
     * in a single slash. Surrounding slashes are dropped in both directions — a trailing one would double
     * up against the date segment, and a leading one would make every key start with {@code /}, which no
     * producer writes, so it would list nothing while reporting success.
     */
    private static String basePrefix(String pathPrefix) {
        String trimmed = StringUtils.strip(StringUtils.trimToEmpty(pathPrefix), "/");
        return trimmed.isEmpty() ? "" : trimmed + "/";
    }
}
