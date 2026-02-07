package dev.emi.emi.registry;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.runtime.EmiLog;

public class GTTierSort {
    private static Method getRecipeTier;
    private static Class<?> gtEmiRecipe;
    private static Field recipeF;
    private static boolean ok = false;

    static {
        try {
            Class<?> gtRecipeHelper = Class.forName("com.gregtechceu.gtceu.api.recipe.RecipeHelper");
            Class<?> gtRecipe = Class.forName("com.gregtechceu.gtceu.api.recipe.GTRecipe");
            getRecipeTier = gtRecipeHelper.getDeclaredMethod("getRecipeEUtTier", gtRecipe);
            gtEmiRecipe = Class.forName("com.gregtechceu.gtceu.integration.emi.recipe.GTEmiRecipe");
            recipeF = gtEmiRecipe.getDeclaredField("recipe");
            recipeF.setAccessible(true);
            ok = true;
        } catch (Throwable ignore) {
            EmiLog.info("[GTTierSort] Failed to load GT classes!");
        }
    }

    public static boolean isGtRecipe(EmiRecipe recipe) {
        return ok && gtEmiRecipe.isInstance(recipe);
    }

    public static boolean isSorted(List<EmiRecipe> recipes) {
        if (recipes.isEmpty() || !isGtRecipe(recipes.get(0))) {
            return true;
        }
        int min = 0;

        try {
            for (EmiRecipe recipe : recipes) {
                int tier = (Integer) getRecipeTier.invoke(null, recipeF.get(recipe));
                if (tier < 0 || tier > 15) {
                    tier = 15;
                }
                if (tier < min) return false;
                min = tier;
            }
        } catch (Throwable ignore) {}
        return true;
    }

    public static List<EmiRecipe> sortLowTierFirst(List<EmiRecipe> recipes) {
        if (!ok) return recipes;
        for (EmiRecipe recipe : recipes) {
            if (gtEmiRecipe.isInstance(recipe)) {
                break;
            }
            return recipes;
        }
        @SuppressWarnings("unchecked")
        List<EmiRecipe>[] temp = new List[16];

        for (int i = 0; i < 16; i++) {
            temp[i] = new ArrayList<>();
        }

        try {
            for (EmiRecipe recipe : recipes) {
                int tier = (Integer) getRecipeTier.invoke(null, recipeF.get(recipe));
                if (tier < 0 || tier > 15) {
                    tier = 15;
                }
                temp[tier].add(recipe);
            }
        } catch (Throwable e) {
            EmiLog.error("[GTTierSort] Failed to sort:", e);
            return recipes;
        }

        return Arrays.stream(temp).flatMap(List::stream).toList();
    }

}
