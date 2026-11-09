package eu.tenderintel.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.tenderintel.IntegrationTest;
import eu.tenderintel.TestApi;
import eu.tenderintel.TestApi.Session;
import eu.tenderintel.tender.TenderFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class MatchingIntegrationTest {

    static final String PROFILE = """
            {"name":"Kowalski Budownictwo","registrationCountry":"PL","industry":"Budownictwo",
             "website":"https://example.com","countriesOfInterest":["PL","DE"],"contractTypes":["WORKS"],
             "companySize":"SMALL","employees":40,"yearsOfExperience":12,
             "minContractValueEur":100000,"maxContractValueEur":3000000,"cpvCodes":["45200000"]}
            """;

    @Autowired
    MockMvc mvc;
    @Autowired
    TestApi api;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MatchingService matching;

    @BeforeEach
    void fixtures() {
        TenderFixtures.insert(jdbc);
    }

    private String org(Session s) throws Exception {
        return api.readJson(mvc.perform(get("/api/v1/organizations").with(s.bearer())).andReturn().getResponse())
                .get(0).get("id").asString();
    }

    private Integer storedScore(String org, String tender) {
        return jdbc.query("select score from tender_matches where organization_id = ?::uuid and tender_id = ?::uuid",
                rs -> rs.next() ? rs.getInt(1) : null, org, tender);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        Instant end = Instant.now().plus(Duration.ofSeconds(15));
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as("condition within 15 s").isBefore(end);
            Thread.sleep(50);
        }
    }

    @Test
    void profileIsPrivateAndEditableByAdminsOnly() throws Exception {
        Session alice = api.registerVerifyLogin("alice-" + System.nanoTime() + "@match.test");
        Session mallory = api.registerVerifyLogin("mallory-" + System.nanoTime() + "@match.test");
        Session bob = api.registerVerifyLogin("bob-" + System.nanoTime() + "@match.test");
        String org = org(alice);
        String base = "/api/v1/organizations/" + org + "/company-profile";
        jdbc.update("insert into organization_members (organization_id, user_id, role) values (?::uuid, ?::uuid, "
                + "'TEAM_MEMBER')", org, bob.userId());

        mvc.perform(get(base).with(alice.bearer())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("profile_missing"));
        mvc.perform(put(base).with(bob.bearer()).contentType(MediaType.APPLICATION_JSON).content(PROFILE))
                .andExpect(status().isForbidden());
        mvc.perform(put(base).with(mallory.bearer()).contentType(MediaType.APPLICATION_JSON).content(PROFILE))
                .andExpect(status().isNotFound());

        mvc.perform(put(base + "?lang=pl").with(alice.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(PROFILE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Kowalski Budownictwo"))
                .andExpect(jsonPath("$.countriesOfInterest[1]").value("DE"))
                .andExpect(jsonPath("$.contractTypes[0]").value("WORKS"))
                .andExpect(jsonPath("$.cpvCodes[0]").value("45200000"));

        // Members read, outsiders do not even learn that the organization exists.
        mvc.perform(get(base).with(bob.bearer())).andExpect(status().isOk());
        mvc.perform(get(base).with(mallory.bearer())).andExpect(status().isNotFound());

        // Validation: inverted value range, bad CPV, unknown contract type.
        mvc.perform(put(base).with(alice.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(PROFILE.replace("\"minContractValueEur\":100000", "\"minContractValueEur\":9000000")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_value_range"));
        mvc.perform(put(base).with(alice.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(PROFILE.replace("45200000", "4520"))).andExpect(status().isBadRequest());
        mvc.perform(put(base).with(alice.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(PROFILE.replace("[\"WORKS\"]", "[\"BRIBES\"]"))).andExpect(status().isBadRequest());

        // Certifications and references.
        String cert = api.readJson(mvc.perform(post(base + "/certifications").with(alice.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ISO 9001\",\"issuer\":\"TÜV\",\"validUntil\":\"2028-01-31\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse()).get("id").asString();
        mvc.perform(post(base + "/certifications").with(bob.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ISO 14001\"}")).andExpect(status().isForbidden());
        mvc.perform(get(base).with(bob.bearer()))
                .andExpect(jsonPath("$.certifications[0].name").value("ISO 9001"));
        mvc.perform(delete(base + "/certifications/" + cert).with(mallory.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(delete(base + "/certifications/" + cert).with(alice.bearer()))
                .andExpect(status().isNoContent());
        mvc.perform(get(base).with(alice.bearer())).andExpect(jsonPath("$.certifications").isEmpty());

        assertThat(jdbc.queryForObject("select count(*) from audit_logs where organization_id = ?::uuid "
                + "and action = 'COMPANY_PROFILE_UPDATED'", Integer.class, org)).isPositive();
    }

    @Test
    void matchesAreComputedExplainedAndShownOnTheDashboard() throws Exception {
        Session alice = api.registerVerifyLogin("alice-" + System.nanoTime() + "@match.test");
        Session mallory = api.registerVerifyLogin("mallory-" + System.nanoTime() + "@match.test");
        String org = org(alice);
        String profile = "/api/v1/organizations/" + org + "/company-profile";
        String matches = "/api/v1/organizations/" + org + "/matches";

        mvc.perform(get("/api/v1/organizations/" + org + "/dashboard").with(alice.bearer()))
                .andExpect(jsonPath("$.hasProfile").value(false))
                .andExpect(jsonPath("$.topMatches").isEmpty());
        mvc.perform(get(matches + "/" + TenderFixtures.T1).with(alice.bearer())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("profile_missing"));

        mvc.perform(put(profile).with(alice.bearer()).contentType(MediaType.APPLICATION_JSON).content(PROFILE))
                .andExpect(status().isOk());
        // T1: DE, 45200000, WORKS, 2.5 M EUR → 35 + 15 (country only: no base region, no tender NUTS) + 15 + 10
        // + 5 (no keywords declared) + 0 (no references) = 80.
        await(() -> Integer.valueOf(80).equals(storedScore(org, TenderFixtures.T1)));
        // T2: same division only (9) + 15 + 15 + 10 + 5 → 54: stored, but below the "good match" threshold.
        assertThat(storedScore(org, TenderFixtures.T2)).isEqualTo(54);
        // T3 is cancelled: never stored.
        assertThat(storedScore(org, TenderFixtures.T3)).isNull();

        mvc.perform(get(matches + "?lang=pl").with(alice.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.tender.id == '" + TenderFixtures.T1 + "')].score").value(80))
                .andExpect(jsonPath("$.items[?(@.tender.id == '" + TenderFixtures.T2 + "')]").isEmpty());
        mvc.perform(get(matches + "?minScore=40").with(alice.bearer()))
                .andExpect(jsonPath("$.items[?(@.tender.id == '" + TenderFixtures.T2 + "')].score").value(54));
        mvc.perform(get(matches + "?minScore=10").with(alice.bearer())).andExpect(status().isBadRequest());
        mvc.perform(get(matches).with(mallory.bearer())).andExpect(status().isNotFound());

        JsonNode explained = api.readJson(mvc.perform(get(matches + "/" + TenderFixtures.T1).with(alice.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse());
        assertThat(explained.get("score").asInt()).isEqualTo(80);
        assertThat(explained.get("breakdown").get("met").get(0).get("code").asString()).isEqualTo("CPV_MATCH");
        assertThat(explained.get("breakdown").get("missing").toString()).contains("PROFILE_NO_REFERENCES")
                .contains("PROFILE_NO_BASE");
        assertThat(explained.get("tender").get("title").asString()).isEqualTo("Neubau einer Grundschule");
        // Explanations are live, also for tenders that are not stored.
        mvc.perform(get(matches + "/" + TenderFixtures.T3).with(alice.bearer())).andExpect(status().isOk());
        mvc.perform(get(matches + "/" + TenderFixtures.T1).with(mallory.bearer())).andExpect(status().isNotFound());

        // A reference in the same sector raises both scores (recomputed after the change).
        mvc.perform(post(profile + "/references").with(alice.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Budowa szkoły podstawowej","client":"Gmina Testowa","countryCode":"PL",
                                 "value":1200000,"currency":"PLN","yearCompleted":2024,"cpvCode":"45214200"}
                                """))
                .andExpect(status().isCreated());
        await(() -> Integer.valueOf(85).equals(storedScore(org, TenderFixtures.T1)));
        assertThat(storedScore(org, TenderFixtures.T2)).isEqualTo(59);

        mvc.perform(get("/api/v1/organizations/" + org + "/dashboard?lang=pl").with(alice.bearer()))
                .andExpect(jsonPath("$.hasProfile").value(true))
                .andExpect(jsonPath("$.goodMatches").value(matching.countAtLeast(UUID.fromString(org), 60)))
                .andExpect(jsonPath("$.topMatches[0].score").value(85));
    }

    @Test
    void newOrChangedTendersAreScoredForEveryProfile() throws Exception {
        Session alice = api.registerVerifyLogin("alice-" + System.nanoTime() + "@match.test");
        String org = org(alice);
        mvc.perform(put("/api/v1/organizations/" + org + "/company-profile").with(alice.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(PROFILE)).andExpect(status().isOk());
        await(() -> storedScore(org, TenderFixtures.T1) != null);

        // The tender changes: value above the declared maximum → 65 (still stored).
        jdbc.update("update tenders set estimated_value_eur = 9000000 where id = ?::uuid", TenderFixtures.T1);
        matching.rescoreTender(UUID.fromString(TenderFixtures.T1));
        assertThat(storedScore(org, TenderFixtures.T1)).isEqualTo(65);

        // The tender is cancelled → its match disappears.
        jdbc.update("update tenders set status = 'CANCELLED' where id = ?::uuid", TenderFixtures.T1);
        matching.rescoreTender(UUID.fromString(TenderFixtures.T1));
        assertThat(storedScore(org, TenderFixtures.T1)).isNull();
    }

    @Test
    void locationKeywordsDismissalsAndPreparationTime() throws Exception {
        Session ola = api.registerVerifyLogin("v2-" + System.nanoTime() + "@match.test");
        String org = org(ola);
        // T2 (Łódź, PL711) and T1 (Germany) – give them places; base in Kraków (PL213), radius 150 km, STRICT.
        jdbc.update("update tenders set nuts_code = 'PL711' where id = ?::uuid", TenderFixtures.T2);
        jdbc.update("update tenders set nuts_code = 'DE300' where id = ?::uuid", TenderFixtures.T1);
        String body = """
                {"name":"Drogbud Kraków","registrationCountry":"PL","countriesOfInterest":["PL","DE"],
                 "contractTypes":["WORKS"],"cpvCodes":["45000000"],
                 "matching":{"baseNuts":"PL213","maxDistanceKm":150,"regionMode":"STRICT","extraRegions":[],
                   "suppliesNationwide":true,"includeKeywords":["drog"],"excludeKeywords":[],"excludedCpv":[],
                   "minDaysToDeadline":null}}
                """;
        String profile = "/api/v1/organizations/" + org + "/company-profile";
        mvc.perform(put(profile).with(ola.bearer()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matching.baseNuts").value("PL213"))
                .andExpect(jsonPath("$.matching.baseName").value("Miasto Kraków"))
                .andExpect(jsonPath("$.matching.includeKeywords[0]").value("drog"));
        // Łódź is ~190 km from Kraków: outside 150 km in STRICT mode → not a match; Berlin far away too.
        await(() -> storedScore(org, TenderFixtures.T2) == null && storedScore(org, TenderFixtures.T1) == null);
        JsonNode t2 = api.readJson(mvc.perform(get("/api/v1/organizations/" + org + "/matches/" + TenderFixtures.T2)
                .with(ola.bearer())).andReturn().getResponse());
        assertThat(t2.get("breakdown").get("excluded").asBoolean()).isTrue();
        assertThat(t2.get("breakdown").get("distanceKm").asInt()).isBetween(150, 230);

        // A wider radius brings Łódź in; the keyword "drog" matches "Roboty drogowe" in its description.
        mvc.perform(put(profile).with(ola.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("\"maxDistanceKm\":150", "\"maxDistanceKm\":300"))).andExpect(status().isOk());
        await(() -> storedScore(org, TenderFixtures.T2) != null);
        JsonNode list = api.readJson(mvc.perform(get("/api/v1/organizations/" + org + "/matches?minScore=40")
                .with(ola.bearer())).andReturn().getResponse());
        assertThat(list.get("items").toString()).contains(TenderFixtures.T2).contains("KEYWORD_MATCH")
                .contains("WITHIN_DISTANCE");

        // Too little time to prepare: T2 closes on 2026-11-20 – with 60 days required it is left out of the list.
        mvc.perform(put(profile).with(ola.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("\"maxDistanceKm\":150", "\"maxDistanceKm\":300")
                        .replace("\"minDaysToDeadline\":null", "\"minDaysToDeadline\":60"))).andExpect(status().isOk());
        await(() -> storedScore(org, TenderFixtures.T2) != null);
        mvc.perform(get("/api/v1/organizations/" + org + "/matches?minScore=40").with(ola.bearer()))
                .andExpect(jsonPath("$.items[?(@.tender.id == '" + TenderFixtures.T2 + "')]").isEmpty());
        mvc.perform(put(profile).with(ola.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("\"maxDistanceKm\":150", "\"maxDistanceKm\":300"))).andExpect(status().isOk());
        await(() -> storedScore(org, TenderFixtures.T2) != null);

        // "Not relevant" hides it and is counted; undo brings it back.
        String dismissal = "/api/v1/organizations/" + org + "/matches/" + TenderFixtures.T2 + "/dismissal";
        mvc.perform(put(dismissal).with(ola.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"LOCATION\"}")).andExpect(status().isNoContent());
        mvc.perform(put(dismissal).with(ola.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"BOGUS\"}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/organizations/" + org + "/matches?minScore=40").with(ola.bearer()))
                .andExpect(jsonPath("$.items[?(@.tender.id == '" + TenderFixtures.T2 + "')]").isEmpty());
        mvc.perform(get("/api/v1/organizations/" + org + "/matches/dismissal-reasons").with(ola.bearer()))
                .andExpect(jsonPath("$.LOCATION").value(1));
        mvc.perform(delete(dismissal).with(ola.bearer())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/organizations/" + org + "/matches?minScore=40").with(ola.bearer()))
                .andExpect(jsonPath("$.items[?(@.tender.id == '" + TenderFixtures.T2 + "')]").isNotEmpty());

        // Unknown regions are rejected; the region list is public reference data.
        mvc.perform(put(profile).with(ola.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("PL213", "PL999"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_region"));
        mvc.perform(get("/api/v1/regions").param("country", "PL").param("level", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(17));
        // Any European country; Greece is asked for by its ISO code (NUTS calls it EL).
        mvc.perform(get("/api/v1/regions").param("country", "GR").param("level", "3"))
                .andExpect(jsonPath("$[0].code").value(startsWith("EL")));
        mvc.perform(get("/api/v1/regions").param("country", "DE").param("level", "2"))
                .andExpect(jsonPath("$.length()").value(greaterThan(30)));
        // Without a country: every country at once (one request for the "extra regions" list), with ISO codes.
        mvc.perform(get("/api/v1/regions").param("level", "2"))
                .andExpect(jsonPath("$.length()").value(greaterThan(300)))
                .andExpect(jsonPath("$[?(@.code == 'EL30')].country").value(contains("GR")));
        mvc.perform(get("/api/v1/regions").param("level", "0"))
                .andExpect(jsonPath("$[?(@.code == 'PL')].name").value(contains("Polska")));
        // Keywords with commas/quotes are stored as data, not SQL.
        mvc.perform(put(profile).with(ola.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("[\"drog\"]", "[\"a,b\", \"x'y\\\"z\"]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matching.includeKeywords[0]").value("a,b"))
                .andExpect(jsonPath("$.matching.includeKeywords[1]").value("x'y\"z"));
    }
}
