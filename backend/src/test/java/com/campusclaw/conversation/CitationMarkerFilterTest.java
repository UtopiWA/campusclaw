package com.campusclaw.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CitationMarkerFilterTest {

    @Test
    void removesCitationMarkersAcrossDeltaBoundaries() {
        CitationMarkerFilter filter = new CitationMarkerFilter();

        String result = filter.accept("没有依据[")
                + filter.accept("12")
                + filter.accept("]，继续回答[3]。")
                + filter.finish();

        assertThat(result).isEqualTo("没有依据，继续回答。");
    }

    @Test
    void preservesNonCitationBracketsAndIncompleteText() {
        CitationMarkerFilter filter = new CitationMarkerFilter();

        String result = filter.accept("数组[a]与未结束[") + filter.finish();

        assertThat(result).isEqualTo("数组[a]与未结束[");
    }
}
