package com.shiroha.mmdskin.ui.about;

import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

class SponsorCreditsTest {
    @Test
    void emptyListNeedsNoInventedSponsorNames() {
        assertTrue(SponsorCredits.read(new StringReader("[]")).isEmpty());
    }

    @Test
    void readsOptionalDetailsAndSkipsDamagedEntries() {
        var sponsors = SponsorCredits.read(new StringReader("""
                [null, {}, {"name":true}, {"name":"  "},
                 {"name":"Supporter", "description":"Thanks", "url":"https://example.com/thanks"},
                 {"name":"Anonymous"}]
                """));
        assertEquals(2, sponsors.size());
        assertEquals(new SponsorCredits.Sponsor("Supporter", "Thanks", "https://example.com/thanks"), sponsors.get(0));
        assertEquals(new SponsorCredits.Sponsor("Anonymous", "", ""), sponsors.get(1));
    }

    @Test
    void invalidLinksKeepTheSponsorCreditWithoutAnAction() {
        var sponsors = SponsorCredits.read(new StringReader("""
                [{"name":"One", "url":"file:///tmp/example"},
                 {"name":"Two", "url":"javascript:alert(1)"},
                 {"name":"Three", "url":"not a link"}]
                """));
        assertEquals(3, sponsors.size());
        assertTrue(sponsors.stream().allMatch(sponsor -> sponsor.url().isEmpty()));
    }
}
