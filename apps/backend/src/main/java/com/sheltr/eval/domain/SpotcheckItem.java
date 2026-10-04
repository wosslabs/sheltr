package com.sheltr.eval.domain;

import java.util.List;

/**
 * @param judgeFaithful true when the judge model found every sentence supported
 * @param humanFaithful filled in by the maintainer; null until scored
 */
public record SpotcheckItem(String id, String question, String answer, List<String> passages,
                            boolean judgeFaithful, Boolean humanFaithful) {
}
