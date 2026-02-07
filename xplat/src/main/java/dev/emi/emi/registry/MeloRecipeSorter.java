package dev.emi.emi.registry;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.runtime.EmiLog;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntRBTreeMap;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * A recipe sorter created by aMelonRind.
 * Aims to push large amounts of similar recipes to the end of list,
 *  making unique recipes appear at front.
 * Should be useful when playing GregTech.
 */
public class MeloRecipeSorter {
    private static long totalTime = 0;

    public static void logTotalTime() {
        EmiLog.info("[MeloRecipeSorter] Total time: " + totalTime + "ms");
        totalTime = 0;
    }

    public static List<EmiRecipe> sort(@Nullable final EmiRecipeCategory category, final List<EmiRecipe> input) {
        if (input.size() < 16) {
            // too small, doesn't worth sorting.
            return input;
        }

        long startTime = System.currentTimeMillis();
        List<Holder> recipes = new ArrayList<>(input.stream().map(Holder::new).toList());

        List<List<Holder>> ordered = new ArrayList<>();
        Object2IntMap<String> pathFreq = new Object2IntRBTreeMap<>();
        Comparator<Object2IntMap.Entry<String>> comparator = Comparator.comparingInt(Object2IntMap.Entry::getIntValue);
        while (true) {
            // find the most word occurrence
            Optional<Object2IntMap.Entry<String>> maxWordEntry =
                    findMaxWord(recipes, 3, 3, List.of(), comparator);

            if (maxWordEntry.isEmpty()) break;
            String maxWord = maxWordEntry.get().getKey();

            // sift out possibly unrelated recipes
            List<Holder> cache = recipes.stream().filter(r -> r.words.contains(maxWord)).toList();
            Set<String> blacklist = new HashSet<>();
            blacklist.add(maxWord);
            int limit = maxWordEntry.get().getIntValue() * 3 / 5;
            while (true) {
                maxWordEntry = findMaxWord(cache, 2, limit, blacklist, comparator);
                if (maxWordEntry.isEmpty()) break;
                String word = maxWordEntry.get().getKey();
                cache = cache.stream().filter(r -> r.words.contains(word)).toList();
                blacklist.add(word);
            }

            // find the most path occurrence
            for (Holder recipe : cache) {
                pathFreq.put(recipe.path, pathFreq.getOrDefault(recipe.path, 0) + 1);
            }

            String maxPath = pathFreq.object2IntEntrySet()
                    .stream()
                    .filter(e -> e.getIntValue() > 2)
                    .max(comparator)
                    .map(Map.Entry::getKey)
                    .orElse(null);

            pathFreq.clear();
            if (maxPath != null) {
                cache = cache.stream().filter(e -> maxPath.equals(e.path)).toList();
            }

            if (cache.isEmpty()) break;
            ordered.add(cache);
            recipes.removeAll(cache);
        }

        ordered.add(recipes);
        Collections.reverse(ordered);

        List<EmiRecipe> result = ordered.stream()
                .flatMap(g -> g.stream().sorted(Comparator.comparing(Holder::getId)))
                .map(Holder::getRecipe)
                .toList();

        if (GTTierSort.isGtRecipe(input.get(0))) {
            result = GTTierSort.sortLowTierFirst(result);
        }

        // it adds around 66% of baking search time impact, I think it's worth it since it's sorted.
        long time = System.currentTimeMillis() - startTime;
        totalTime += time;
//        String categoryName = category == null
//                ? "Unknown recipe category"
//                : "Recipe category " + category.getId();
//        EmiLog.info(
//                "[MeloRecipeSorter] " + categoryName + " has " + input.size() + " recipes. " +
//                "Took " + time + "ms to sort into " + ordered.size() + " groups."
//        );
        return result;
    }

    private static Optional<Object2IntMap.Entry<String>> findMaxWord(
            Iterable<Holder> recipes,
            int minLen,
            int minCount,
            Collection<String> blacklist,
            Comparator<Object2IntMap.Entry<String>> comparator
    ) {
        Object2IntMap<String> wordFreq = new Object2IntRBTreeMap<>();
        for (Holder recipe : recipes) {
            for (String word : recipe.words) {
                if (word.length() < minLen) continue;
                wordFreq.put(word, wordFreq.getOrDefault(word, 0) + 1);
            }
        }

        return wordFreq.object2IntEntrySet()
                .stream()
                .filter(e -> e.getIntValue() >= minCount && !blacklist.contains(e.getKey()))
                .max(comparator);
    }

    private static class Holder {
        final EmiRecipe recipe;
        String id = "";
        String path = "";
        List<String> words = List.of();

        Holder(EmiRecipe recipe) {
            this.recipe = recipe;
            resolveId();
        }

        private void resolveId() {
            Identifier idObj = this.recipe.getId();
            if (idObj == null) return;
            this.id = idObj.toString();
            int split = id.lastIndexOf('/', id.length() - 2);
            String name;
            if (split != -1) {
                this.path = id.substring(0, split);
                name = id.substring(split + 1);
            } else {
                name = idObj.getPath();
            }
            split = name.length() - 1;
            if (split >= 0 && name.charAt(split) == '/') {
                while (split >= 0 && name.charAt(split) == '/') {
                    split--;
                }
                if (split < 0) {
                    // obviously edge case, who would make their id look like this?
                    name = "";
                } else {
                    name = name.substring(0, split + 1);
                }
            }
            this.words = Arrays.stream(name.split("[0-9_/.-]+"))
                    .filter(s -> !s.isBlank() && !"from".equals(s))
                    .distinct()
                    .toList();
        }

        String getId() {
            return id;
        }

        EmiRecipe getRecipe() {
            return recipe;
        }
    }
}
