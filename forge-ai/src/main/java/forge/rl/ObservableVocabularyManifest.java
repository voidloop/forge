package forge.rl;

import forge.game.ability.ApiType;
import forge.game.card.CounterType;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;

import java.util.Arrays;

/** Stable Forge-owned vocabulary used by observer-safe graph contracts. */
public final class ObservableVocabularyManifest {
    private ObservableVocabularyManifest() {
    }

    public static String[] apiTypes() {
        return enumNames(ApiType.values());
    }

    public static String[] phaseTypes() {
        return enumNames(PhaseType.values());
    }

    public static String[] zoneTypes() {
        return Arrays.stream(ZoneType.values())
                .map(value -> value.name().toLowerCase(java.util.Locale.ROOT))
                .sorted()
                .toArray(String[]::new);
    }

    public static String[] counterTypes() {
        return CounterType.getValues().stream()
                .map(CounterType::getName)
                .distinct()
                .sorted()
                .toArray(String[]::new);
    }

    public static String[] keywordTypes() {
        return Arrays.stream(Keyword.values())
                .map(Keyword::toString)
                .filter(value -> !value.isEmpty())
                .sorted()
                .toArray(String[]::new);
    }

    private static <T extends Enum<T>> String[] enumNames(T[] values) {
        return Arrays.stream(values)
                .map(Enum::name)
                .sorted()
                .toArray(String[]::new);
    }
}
