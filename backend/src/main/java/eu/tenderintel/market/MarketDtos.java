package eu.tenderintel.market;

import eu.tenderintel.tender.TenderDtos.TenderSummary;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class MarketDtos {

    private MarketDtos() {
    }

    /**
     * What the statistics cover. {@code since}: start of the window allowed by the plan; {@code dataSince}: the oldest
     * award in the database (coverage depends on how far back notices were imported).
     */
    public record Scope(String countryCode, String cpvPrefix, String cpvLabel, int months, LocalDate since,
            LocalDate dataSince) {
    }

    /**
     * {@code priceRatio*}: awarded value / estimated value (quartiles), only when at least {@link MarketService#MIN_SAMPLE}
     * tenders have both values; awards equal to the estimate (±0.5 %) are left out – many award notices repeat the
     * final value as the estimate, and only TED awards count (their prices are net like the estimates; BZP prices are
     * gross). {@code singleBidShare}: share of awards with exactly one offer.
     */
    public record CompetitionStats(long awardedTenders, Double medianOffers, Double singleBidShare, long offersSample,
            Double priceRatioP25, Double priceRatioMedian, Double priceRatioP75, long priceRatioSample,
            BigDecimal medianAwardEur) {
    }

    public record WinnerRow(UUID id, String name, String countryCode, long wins, BigDecimal totalEur,
            LocalDate lastWin) {
    }

    public record AuthorityRow(UUID id, String name, String countryCode, long count, BigDecimal totalEur) {
    }

    public record AwardRow(UUID tenderId, String title, String countryCode, UUID authorityId, String authorityName,
            UUID winnerId, String winnerName, BigDecimal valueEur, Integer offers, LocalDate awardDate) {
    }

    public record Bucket(String key, String label, long count) {
    }

    public record TenderMarket(Scope scope, CompetitionStats stats, List<WinnerRow> topWinners,
            List<AwardRow> recentAwards, AuthoritySummary authority) {
    }

    public record AuthoritySummary(UUID id, String name, CompetitionStats stats, List<WinnerRow> topWinners) {
    }

    public record AuthorityProfile(UUID id, String name, String countryCode, String city, String nationalId,
            String website, Scope scope, long tendersPublished, long openNow, long cancelled, CompetitionStats stats,
            List<Bucket> topCpv, List<WinnerRow> topWinners, List<AwardRow> recentAwards,
            List<TenderSummary> openTenders) {
    }

    public record CompanyProfile(UUID id, String name, String countryCode, String nationalId, Boolean sme,
            Scope scope, long wins, BigDecimal totalEur, Double medianOffers, List<Bucket> topCpv,
            List<Bucket> countries, List<AuthorityRow> topAuthorities, List<AwardRow> recentWins, boolean watched) {
    }

    public record CompanyHit(UUID id, String name, String countryCode, String nationalId, long wins) {
    }

    public record AuthorityHit(UUID id, String name, String countryCode, String city, long tenders) {
    }

    public record Competitor(UUID id, String name, String countryCode, String nationalId, long winsInWindow,
            BigDecimal totalEurInWindow, LocalDate lastWin, String note, java.time.Instant addedAt) {
    }

    public record CompetitorsView(List<Competitor> items, int limit, int months) {
    }

    public record WatchRequest(@jakarta.validation.constraints.Size(max = 500) String note) {
    }
}
