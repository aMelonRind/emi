package dev.emi.emi.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import dev.emi.emi.runtime.EmiLog;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import com.google.common.collect.Lists;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.emi.emi.EmiPort;
import dev.emi.emi.EmiRenderHelper;
import dev.emi.emi.EmiUtil;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.recipe.EmiResolutionRecipe;
import dev.emi.emi.api.render.EmiSlotOverlay;
import dev.emi.emi.api.render.EmiTooltipComponents;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.bom.BoM;
import dev.emi.emi.bom.ChanceMaterialCost;
import dev.emi.emi.bom.ChanceState;
import dev.emi.emi.bom.FlatMaterialCost;
import dev.emi.emi.bom.FoldState;
import dev.emi.emi.bom.MaterialNode;
import dev.emi.emi.bom.ProgressState;
import dev.emi.emi.config.EmiConfig;
import dev.emi.emi.data.EmiRecipeCategoryProperties;
import dev.emi.emi.input.EmiBind;
import dev.emi.emi.input.EmiInput;
import dev.emi.emi.registry.EmiStackList;
import dev.emi.emi.runtime.EmiDrawContext;
import dev.emi.emi.runtime.EmiFavorites;
import dev.emi.emi.runtime.EmiHistory;
import dev.emi.emi.screen.StackBatcher.Batchable;
import dev.emi.emi.screen.tooltip.EmiTooltip;
import dev.emi.emi.screen.tooltip.RecipeTooltipComponent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.tooltip.TooltipComponent;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;

public class BoMScreen extends Screen {
	private static final int NODE_WIDTH = 30;
	private static final int NODE_HORIZONTAL_SPACING = 8;
	private static final int NODE_VERTICAL_SPACING = 20;
	private static final int COST_HORIZONTAL_SPACING = 8;
	private static final int[] BOUNDING_BOX_COLORS = {
			0x7FFF0000, 0x7F00FF00, 0x7F0000FF, 0x7FFFFF00, 0x7FFF00FF, 0x7F00FFFF,
			0x7FFF7F00, 0x7F00FF7F, 0x7F7F00FF, 0x7F7FFF00, 0x7F007FFF, 0x7FFF007F
	};
	private static StackBatcher batcher = new StackBatcher();
	private static int zoom = 0;
	private Bounds batches = new Bounds(-24, -50, 48, 26);
	private Bounds mode = new Bounds(-24, -50, 16, 16);
	private Bounds help = new Bounds(0, 0, 16, 16);
	// treat this as the tree's offset relative to the camera,
	// not vise versa, which made me confused for a while.
	private double offX, offY;
	private @Nullable Runnable focuser = null;
	private List<Node> nodes = Lists.newArrayList();
	private List<Cost> costs = Lists.newArrayList();
	private EmiPlayerInventory playerInv;
	private boolean hasRemainders = false;;
	public HandledScreen<?> old;
	private int nodeWidth = 0;
	private int nodeHeight = 0;
	private int lastMouseX, lastMouseY;
	private double scrollAcc = 0;
	private Bounds camera = Bounds.EMPTY;
	private Bounds stackCamera = Bounds.EMPTY;
	private Bounds batcherCamera = null;

	// debug
	private int boxColorIndex = 0;
	private int lastFps = 0;
	private int lastUspf = 0;
	private int frameCounter = 0;
	private long frameCounterStart = System.nanoTime();
	private int lastBatcherUs = -1;
	private long batcherStart = System.nanoTime();

	public BoMScreen(HandledScreen<?> old) {
		super(EmiPort.translatable("screen.emi.recipe_tree"));
		this.old = old;
	}

	public void init() {
		if (BoM.tree != null) {
			offY = height / -3;
		} else {
			offY = 0;
		}
		recalculateTree();
		Runnable focus = this.focuser;
		if (focus != null) {
			focus.run();
			this.focuser = null;
		}
	}

	public void recalculateTree() {
		help = new Bounds(width - 18, height - 18, 16, 16);
		if (BoM.tree != null) {
			TreeVolume volume = addNewNodes(BoM.tree.goal, BoM.tree.batches, 1, 0, ChanceState.DEFAULT);
			nodes = volume.nodes;
			int horizontalOffset = (volume.getMaxRight() + volume.getMinLeft()) / 2;
			for (Node node : volume.nodes) {
				node.x -= horizontalOffset;
			}
			if (!volume.nodes.isEmpty()) {
				Node node = volume.nodes.get(0);
				int width = textRenderer.getWidth("x" + BoM.tree.batches);
				batches = new Bounds(node.x + node.width / 2 + 6, node.y - 10, width + 12, 22);
			}

			nodeWidth = volume.getMaxRight() - volume.getMinLeft();
			nodeHeight = getNodeHeight(BoM.tree.goal);
			playerInv = EmiPlayerInventory.of(client.player);
			BoM.tree.calculateProgress(playerInv);
			Map<EmiIngredient, FlatMaterialCost> progressCosts = BoM.tree.cost.costs.values().stream()
				.collect(Collectors.toMap(c -> c.ingredient, c -> c));
			Map<EmiIngredient, ChanceMaterialCost> chanceProgressCosts = BoM.tree.cost.chanceCosts.values().stream()
				.collect(Collectors.toMap(c -> c.ingredient, c -> c));
				
			costs.clear();
			BoM.tree.calculateCost();

			List<FlatMaterialCost> treeCosts = Stream.concat(
				BoM.tree.cost.costs.values().stream(),
				BoM.tree.cost.chanceCosts.values().stream()
			).sorted((a, b) -> Integer.compare(
				EmiStackList.getIndex(a.ingredient.getEmiStacks().get(0)),
				EmiStackList.getIndex(b.ingredient.getEmiStacks().get(0))
			)).toList();
			int cy = nodeHeight * NODE_VERTICAL_SPACING * 2;
			int costX = 0;
			for (FlatMaterialCost node : treeCosts) {
				Cost cost = new Cost(node, costX, cy, false);
				if (BoM.craftingMode) {
					if (node instanceof ChanceMaterialCost cmc) {
						if (!chanceProgressCosts.containsKey(node.ingredient)) {
							cost.alreadyDone = node.getEffectiveAmount();
						} else {
							ChanceMaterialCost progress = chanceProgressCosts.get(node.ingredient);
							cost.alreadyDone = (long) Math.ceil(cmc.amount * cmc.chance - progress.amount * progress.chance);
						}
					} else {
						if (!progressCosts.containsKey(node.ingredient)) {
							cost.alreadyDone = node.amount;
						} else {
							FlatMaterialCost progress = progressCosts.get(node.ingredient);
							cost.alreadyDone = node.amount - progress.amount;
						}
					}
				}
				costs.add(cost);
				costX += 16 + COST_HORIZONTAL_SPACING + EmiRenderHelper.getAmountOverflow(cost.getAmountText());
			}
			int costOffset = (costX - COST_HORIZONTAL_SPACING) / 2;
			for (Cost cost : costs) {
				cost.x -= costOffset;
			}

			int totalCostWidth = textRenderer.getWidth(EmiPort.translatable("emi.total_cost"));
			mode = new Bounds(totalCostWidth / 2 + 4, cy - 20, 16, 16);

			List<Cost> remainders = Lists.newArrayList();

			List<FlatMaterialCost> remainderCosts = Stream.concat(
				BoM.tree.cost.remainders.values().stream(),
				BoM.tree.cost.chanceRemainders.values().stream()
			).sorted((a, b) -> Integer.compare(
				EmiStackList.getIndex(a.ingredient.getEmiStacks().get(0)),
				EmiStackList.getIndex(b.ingredient.getEmiStacks().get(0))
			)).toList();
			cy += 40;
			int remainderX = 0;
			for (FlatMaterialCost node : remainderCosts) {
				if (node.getEffectiveAmount() <= 0) {
					continue;
				}
				Cost cost = new Cost(node, remainderX, cy, true);
				remainders.add(cost);
				remainderX += 16 + COST_HORIZONTAL_SPACING + EmiRenderHelper.getAmountOverflow(cost.getAmountText());
			}
			costOffset = (remainderX - COST_HORIZONTAL_SPACING) / 2;
			for (Cost cost : remainders) {
				cost.x -= costOffset;
			}
			costs.addAll(remainders);
			hasRemainders = !remainders.isEmpty();
		} else {
			nodes = Lists.newArrayList();
			costs.clear();
		}
		CachedText.invalidate();
		batcher.repopulate();
	}

	@Override
	public void render(DrawContext raw, int mouseX, int mouseY, float delta) {
		EmiDrawContext context = EmiDrawContext.wrap(raw);
		this.renderBackgroundTexture(context.raw());
		lastMouseX = mouseX;
		lastMouseY = mouseY;
		float scale = getScale();
		int scaledWidth = (int) (width / scale);
		int scaledHeight = (int) (height / scale);
		// TODO should be the ingredient width if higher
		int contentWidth = nodeWidth * NODE_WIDTH;
		int contentHeight = nodeHeight * NODE_VERTICAL_SPACING + 80;
		int xBound = scaledWidth / 2 + contentWidth - 100;
		int topBound = scaledHeight * 1 / -2 + 20;
		int bottomBound = contentHeight + scaledHeight / 2 - 20;
		offX = MathHelper.clamp(offX, -xBound, xBound);
		offY = MathHelper.clamp(offY, -bottomBound, -topBound);

		int mx = (int) ((mouseX - width / 2) / scale - offX);
		int my = (int) ((mouseY - height / 2) / scale - offY);

		if (EmiConfig.recipeTreeBoundingBoxes) {
			frameCounter++;
			long now = System.nanoTime();
			long elapsed = now - frameCounterStart;
			if (elapsed > 1_000_000_000) {
				lastFps = frameCounter / (int) (elapsed / 1_000_000_000);
				lastUspf = (int) (elapsed / 1000) / frameCounter;
				frameCounterStart = now;
				frameCounter = 0;
			}
			context.drawText(Text.literal(lastFps + " FPS, " + lastUspf + " USPF"), 5, 5);
			// i guess it's safe to call a duplicate?
			batcher.begin(0, 0, 0);
			if (!batcher.isPopulated()) {
				lastBatcherUs = -1;
				batcherStart = System.nanoTime();
				context.drawText(Text.literal("Populating batcher"), 5, 15);
			} else {
				if (lastBatcherUs == -1) {
					lastBatcherUs = (int) ((System.nanoTime() - batcherStart) / 1000);
				}
				context.drawText(Text.literal("Batcher populate frame: " + lastBatcherUs + "us"), 5, 15);
			}
		}

		MatrixStack view = RenderSystem.getModelViewStack();
		view.push();
		view.translate(width / 2, height / 2, 0);
		view.scale(scale, scale, 1);
		view.translate(offX, offY, 0);
		EmiPort.applyModelViewMatrix();

		camera = new Bounds(
				-(scaledWidth / 2) - (int) offX - 2,
				-(scaledHeight / 2) - (int) offY - 2,
				scaledWidth + 4,
				scaledHeight + 4
		);

		if (EmiConfig.recipeTreeBoundingBoxes) {
			int sx = camera.width() / 5;
			int sy = camera.height() / 5;
			camera = new Bounds(
					camera.x() + sx,
					camera.y() + sy,
					camera.width() - sx * 2,
					camera.height() - sy * 2
			);
			// render borders
			context.setColor(1.0f, 0.0f, 0.0f, 0.25f);
			int x = camera.x() + 2, y = camera.y() + 2, w = camera.width() - 4, h = camera.height() - 4;
			context.fill(x, y - h, w, h, -1);
			context.fill(x, y + h, w, h, -1);
			context.fill(x - w, y - h, w, h * 3, -1);
			context.fill(x + w, y - h, w, h * 3, -1);
			context.setColor(1, 1, 1);
			boxColorIndex = 0;
		}

		stackCamera = new Bounds(
				camera.x() - 30,
				camera.y() - 30,
				camera.width() + 60,
				camera.height() + 60
		);

		if (batcherCamera == null || !batcherCamera.contains(stackCamera)) {
			int w = stackCamera.width() * EmiConfig.batcherCameraExpandPercentage / 100;
			int h = stackCamera.height() * EmiConfig.batcherCameraExpandPercentage / 100;
			batcherCamera = new Bounds(
					stackCamera.x() - w,
					stackCamera.y() - h,
					stackCamera.width() + w * 2,
					stackCamera.height() + h * 2
			);
			batcher.repopulate();
			if (EmiConfig.recipeTreeBoundingBoxes) {
				lastBatcherUs = -1;
				batcherStart = System.nanoTime();
			}
		}

		if (BoM.tree != null) {
			batcher.begin(0, 0, 0);
			int cy = nodeHeight * NODE_VERTICAL_SPACING * 2;
			context.drawCenteredText(EmiPort.translatable("emi.total_cost"), 0, cy - 16);
			if (hasRemainders) {
				context.drawCenteredText(EmiPort.translatable("emi.leftovers"), 0, cy - 16 + 40);
			}
			List<AmountRenderInfo> amounts = new ArrayList<>();
			for (Cost cost : costs) {
				cost.render(context, amounts);
			}
			for (Node node : nodes) {
				node.render(context, mx, my, delta, amounts);
			}
			int color = -1;
			if (batches.contains(mx, my)) {
				color = 0xff8099ff;
			}
			context.drawTextWithShadow(EmiPort.literal("x" + BoM.tree.batches),
					batches.x() + 6, batches.y() + batches.height() / 2 - 4, color);

			if (mode.contains(mx, my)) {
				context.setColor(0.5f, 0.6f, 1f, 1f);
			}
			context.drawTexture(EmiRenderHelper.WIDGETS, mode.x(), mode.y(), BoM.craftingMode ? 16 : 0, 146, mode.width(), mode.height());
			context.setColor(1f, 1f, 1f, 1f);
			batcher.draw();

			// batch render amount after batcher
			context.push();
			EmiRenderHelper.renderAmountTranslate(context);
			Bounds textCamera = new Bounds(
					camera.x() - EmiRenderHelper.AMOUNT_TEXT_RIGHT_SHIFT,
					camera.y() - EmiRenderHelper.AMOUNT_TEXT_DOWN_SHIFT,
					camera.width(),
					camera.height()
			);
			for (AmountRenderInfo info : amounts) {
				info.render(context, textCamera);
			}
			context.pop();
		} else {
			context.drawCenteredText(EmiPort.translatable("emi.tree_welcome", EmiRenderHelper.getEmiText()), 0, -72);
			context.drawCenteredText(EmiPort.translatable("emi.no_tree"), 0, -48);
			context.drawCenteredText(EmiPort.translatable("emi.random_tree"), 0, -24);
			context.drawCenteredText(EmiPort.translatable("emi.random_tree_input"), 0, 0);
		}

		view.pop();
		EmiPort.applyModelViewMatrix();

		if (help.contains(mouseX, mouseY)) {
			context.setColor(0.5f, 0.6f, 1f, 1f);
		}
		context.drawTexture(EmiRenderHelper.WIDGETS, help.x(), help.y(), 0, 200, help.width(), help.height());
		context.setColor(1f, 1f, 1f, 1f);

		Hover hover = getHoveredStack(mouseX, mouseY);
		if (hover != null) {
			hover.drawTooltip(this, context, mouseX, mouseY);
		} else if (BoM.tree != null && batches.contains(mx, my)) {
			List<TooltipComponent> list = Lists.newArrayList();
			list.addAll(EmiTooltip.splitTranslate("tooltip.emi.bom.batch_size", BoM.tree.batches));
			list.add(EmiTooltipComponents.of(EmiPort.translatable("tooltip.emi.bom.batch_size.ideal", EmiBind.LEFT_CLICK.getBindText())));
			EmiRenderHelper.drawTooltip(this, context, list, mouseX, mouseY);
		} else if (BoM.tree != null && mode.contains(mx, my)) {
			String key = BoM.craftingMode ? "tooltip.emi.bom.mode.craft" : "tooltip.emi.bom.mode.view";
			List<TooltipComponent> list = EmiTooltip.splitTranslate(key, BoM.tree.batches);
			EmiRenderHelper.drawTooltip(this, context, list, mouseX, mouseY);
		} else if (help.contains(mouseX, mouseY)) {
			List<TooltipComponent> list =  EmiTooltip.splitTranslate("tooltip.emi.bom.help");
			EmiRenderHelper.drawTooltip(this, context, list, width - 18, height - 18, width);
		}
	}

	public Hover getHoveredStack(int mx, int my) {
		float scale = getScale();
		mx = (int) ((mx - width / 2) / scale - offX);
		my = (int) ((my - height / 2) / scale - offY);
		for (Cost cost : costs) {
			if (mx >= cost.x && mx < cost.x + 16 && my >= cost.y && my < cost.y + 16) {
				return new Hover(cost.cost.ingredient).rememberFocus(cost);
			}
		}
		for (Node node : nodes) {
			Hover hover = node.getHover(mx, my);
			if (hover != null) {
				return hover.rememberFocus(node);
			}
		}
		return null;
	}

	public int getNodeHeight(MaterialNode node) {
		if (node.recipe != null && node.state == FoldState.EXPANDED) {
			int i = 1;
			for (MaterialNode n : node.children) {
				i = Math.max(i, getNodeHeight(n));
			}
			if (node.recipe instanceof EmiResolutionRecipe) {
				return i;
			}
			return i + 1;
		}
		return 1;
	}

	public TreeVolume addNewNodes(MaterialNode node, long multiplier, long divisor, int depth, ChanceState chance) {
		if (node.catalyst) {
			multiplier = node.amount;
		} else {
			multiplier = node.amount * (int) Math.ceil(multiplier / (float) divisor);
		}
		if (node.recipe != null && node.children.size() > 0 && node.state == FoldState.EXPANDED) {
			ChanceState produced = chance.produce(node.produceChance);
			if (node.recipe instanceof EmiResolutionRecipe) {
				TreeVolume volume = addNewNodes(node.children.get(0), multiplier, node.divisor, depth, produced);
				volume.nodes.get(0).resolution = node;
				return volume;
			}
			TreeVolume left = null;
			for (int i = 0; i < node.children.size(); i++) {
				ChanceState consumed = produced.consume(node.children.get(i).consumeChance);
				TreeVolume volume = addNewNodes(node.children.get(i), multiplier, node.divisor, depth + 1, consumed);
				if (left == null) {
					left = volume;
				} else {
					left.addToRight(volume);
				}
			}
			left.addHead(node, multiplier, depth * NODE_VERTICAL_SPACING, chance);
			return left;
		}
		return new TreeVolume(node, multiplier, depth * NODE_VERTICAL_SPACING, chance);
	}

	private void drawHorizontalLine(EmiDrawContext context, int x1, int x2, int y) {
		if (y < camera.top() || y > camera.bottom()) {
			return;
		}
		if (x1 < x2) {
			if (x2 < camera.left() || x1 > camera.right()) {
				return;
			}
			context.fill(x1, y, x2 - x1 + 1, 1, -1);
		} else {
			if (x1 < camera.left() || x2 > camera.right()) {
				return;
			}
			context.fill(x2, y, x1 - x2 + 1, 1, -1);
		}
	}

	private void drawVerticalLine(EmiDrawContext context, int x, int y1, int y2) {
		if (x < camera.left() || x > camera.right()) {
			return;
		}
		if (y1 < y2) {
			if (y2 < camera.top() || y1 > camera.bottom()) {
				return;
			}
			context.fill(x, y1, 1, y2 - y1 + 1, -1);
		} else {
			if (y1 < camera.top() || y2 > camera.bottom()) {
				return;
			}
			context.fill(x, y2, 1, y1 - y2 + 1, -1);
		}
	}

	public float getScale() {
		zoom = MathHelper.clamp(zoom, -6, 4);
		int scale = (int) this.client.getWindow().getScaleFactor();
		int desired = scale + zoom;
		if (desired < 1) {
			zoom -= desired - 1;
			desired = 1;
		}
		return (float) desired / scale;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
			this.close();
			return true;
		} else if (this.client.options.inventoryKey.matchesKey(keyCode, scanCode)) {
			this.close();
			return true;
		}
		Function<EmiBind, Boolean> function = bind -> bind.matchesKey(keyCode, scanCode);
		if (function.apply(EmiConfig.back)) {
			EmiHistory.pop();
			return true;
		}
		Hover hover = getHoveredStack(lastMouseX, lastMouseY);
		if (hover != null && hover.stack != null && !hover.stack.isEmpty()) {
			if (function.apply(EmiConfig.favorite)) {
				EmiFavorites.addFavorite(hover.stack, hover.node == null ? null : hover.node.recipe);
			}
		}
		if (EmiInput.isControlDown() && keyCode == GLFW.GLFW_KEY_R) {
			List<EmiRecipe> recipes = EmiApi.getRecipeManager().getRecipes();
			if (recipes.size() > 0) {
				for (int i = 0; i < 100_000; i++) {
					EmiRecipe recipe = recipes.get(EmiUtil.RANDOM.nextInt(recipes.size()));
					if (recipe.supportsRecipeTree()) {
						BoM.setGoal(recipe);
						init();
						return true;
					}
				}
			}
		} else if (EmiInput.isControlDown() && keyCode == GLFW.GLFW_KEY_C) {
			BoM.tree = null;
			init();
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	private boolean getAutoResolutions(Hover hover, BiConsumer<EmiIngredient, EmiRecipe> consumer) {
		EmiPlayerInventory inv = playerInv;
		if (inv != null) {
			List<EmiStack> stacks = hover.stack.getEmiStacks();
			if (stacks.size() > 1) {
				for (EmiStack stack : stacks) {
					if (inv.inventory.containsKey(stack)) {
						consumer.accept(hover.stack, new EmiResolutionRecipe(hover.stack, stack));
						return true;
					}
				}
				for (EmiStack stack : stacks) {
					for (Cost cost : costs) {
						if (cost.cost.ingredient.equals(stack)) {
							consumer.accept(hover.stack, new EmiResolutionRecipe(hover.stack, stack));
							return true;
						}
					}
				}
				consumer.accept(hover.stack, new EmiResolutionRecipe(hover.stack, stacks.get(0)));
				return true;
			} else {
				EmiRecipe recipe = EmiUtil.getRecipeResolution(hover.stack, inv);
				if (recipe != null) {
					consumer.accept(hover.stack, recipe);
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		Hover hover = getHoveredStack((int) mouseX, (int) mouseY);
		float scale = getScale();
		int mx = (int) ((mouseX - width / 2) / scale - offX);
		int my = (int) ((mouseY - height / 2) / scale - offY);
		if (hover != null) {
			if (button == 1 && hover.node != null && hover.node.recipe != null) {
				if (EmiInput.isShiftDown()) {
					BoM.tree.addResolution(hover.node.ingredient, null);
				} else if (!(hover.node.recipe instanceof EmiResolutionRecipe)) {
					if (hover.node.state == FoldState.EXPANDED) {
						hover.node.state = FoldState.COLLAPSED;
					} else {
						hover.node.state = FoldState.EXPANDED;
					}
				}
				recalculateTree();
				return true;
			}
			if (hover.stack != null) {
				if (EmiInput.isShiftDown() && button == 0) {
					if (getAutoResolutions(hover, BoM.tree::addResolution)) {
						recalculateTree();
					}
					return true;
				} else {
					if (button == 0) {
						EmiApi.displayRecipes(hover.stack);
						RecipeScreen.resolve = hover.stack;
						hover.bindFocuser(this);
						MinecraftClient client = MinecraftClient.getInstance();
						// The first init doesn't realize a resolution exists so we do it again. What
						// could go wrong.
						client.currentScreen.init(client, client.currentScreen.width, client.currentScreen.height);
						if (hover.node != null) {
							if (hover.node.recipe != null) {
								EmiApi.focusRecipe(hover.node.recipe);
							}
						}
						return true;
					}
				}
			}
		} else if (mode.contains(mx, my)) {
			MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f));
			BoM.craftingMode = !BoM.craftingMode;
			recalculateTree();
			EmiSlotOverlay.triggerListeners();
		} else if (batches.contains(mx, my) && BoM.tree != null) {
			long ideal = BoM.tree.cost.getIdealBatch(BoM.tree.goal, 1, 1);
			if (ideal != BoM.tree.batches) {
				MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f));
				BoM.tree.batches = ideal;
				recalculateTree();
			}
		}
		Function<EmiBind, Boolean> function = bind -> bind.matchesMouse(button);
		if (function.apply(EmiConfig.back)) {
			EmiHistory.pop();
			return true;
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		scrollAcc += amount;
		amount = (int) scrollAcc;
		scrollAcc %= 1;
		float scale = getScale();
		int mx = (int) ((mouseX - width / 2) / scale - offX);
		int my = (int) ((mouseY - height / 2) / scale - offY);
		if (BoM.tree != null && batches.contains(mx, my)) {
			long adjustment = (long) amount;
			if (EmiInput.isShiftDown()) {
				adjustment *= 16;
			} else if (EmiInput.isControlDown()) {
				if (amount > 0) {
					adjustment = BoM.tree.batches;
				} else {
					adjustment = -BoM.tree.batches / 2;
				}
			}
			if (BoM.tree.batches == 1 && adjustment > 1) {
				BoM.tree.batches = adjustment;
			} else {
				BoM.tree.batches += adjustment;
			}
			BoM.tree.batches = Math.max(1, BoM.tree.batches);
			recalculateTree();
			return true;
		}
		zoom += (int) amount;
		batcherCamera = stackCamera;
		return true;
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		if (button == 0 || button == 2) {
			float scale = getScale();
			offX += deltaX / scale;
			offY += deltaY / scale;
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	@Override
	public void close() {
		MinecraftClient.getInstance().setScreen(old);
	}

	private class Cost {
		public FlatMaterialCost cost;
		public int x, y;
		public long alreadyDone = 0;
		public boolean remainder;
		private CachedText textCache = new CachedText();

		public Cost(FlatMaterialCost cost, int x, int y, boolean remainder) {
			this.cost = cost;
			this.x = x;
			this.y = y;
			this.remainder = remainder;
		}

		public void render(EmiDrawContext context, List<AmountRenderInfo> amounts) {
			if (stackCamera.overlaps(x, y, 16, 16) || !batcher.isPopulated() && batcherCamera.overlaps(x, y, 16, 16)) {
				batcher.render(cost.ingredient, context.raw(), x, y, 0, ~(EmiIngredient.RENDER_AMOUNT | EmiIngredient.RENDER_REMAINDER));
			}

//			EmiRenderHelper.renderAmount(context, x, y, getAmountText());
			// AmountRenderInfo is already culled
			amounts.add(new AmountRenderInfo(x, y, getAmountText()));
		}

		public Text getAmountText() {
			return textCache.getOrCompute(() -> {
				long adjusted = cost.getEffectiveAmount();
				Text totalText;
				if (cost instanceof ChanceMaterialCost cmc) {
					totalText = EmiPort.append(EmiPort.literal("≈"), EmiRenderHelper.getAmountText(cost.ingredient, adjusted))
						.formatted(Formatting.GOLD);
				} else {
					totalText = EmiRenderHelper.getAmountText(cost.ingredient, adjusted);
				}
				if (!remainder && BoM.craftingMode) {
					long amount = alreadyDone;
					if (amount < adjusted) {
						Text amountText = amount == 0 ? EmiPort.literal("0") : (EmiRenderHelper.getAmountText(cost.ingredient, amount));
						MutableText text = EmiPort.append(EmiPort.literal("", Formatting.RED), amountText);
						text = EmiPort.append(text, EmiPort.literal("/"));
						text = EmiPort.append(text, totalText);
						return text;
					}
				}
				return totalText;
			});
		}
	}

	private class Hover {
		public EmiIngredient stack;
		public MaterialNode node, resolve;
		public EmiRecipeCategory category;
		private Runnable focuser;

		public Hover(EmiIngredient stack) {
			this.stack = stack;
		}

		public Hover(EmiIngredient stack, MaterialNode node, MaterialNode resolve) {
			this.stack = stack;
			this.node = node;
			this.resolve = resolve;
		}

		public Hover(EmiRecipeCategory category, MaterialNode node) {
			this.category = category;
			this.node = node;
		}

		public Hover(MaterialNode node) {
			this.node = node;
		}

		public boolean drawTooltip(Screen screen, EmiDrawContext context, int mouseX, int mouseY) {
			if (stack != null) {
				List<TooltipComponent> list = Lists.newArrayList();
				list.addAll(stack.getTooltip());
				if (EmiInput.isShiftDown()) {
					getAutoResolutions(this, (stack, recipe) -> {
						if (node == null || recipe != node.recipe) {
							list.add(new RecipeTooltipComponent(recipe, 0x4488FFAA));
						} else {
							list.add(new RecipeTooltipComponent(recipe));
						}
					});
				} else if (node != null && node.recipe != null) {
					list.add(new RecipeTooltipComponent(node.recipe));
				}
				if (node != null) {
					if (node.consumeChance != 1) {
						list.add(EmiTooltip.chance("consume", node.consumeChance));
					} else if (resolve != null && resolve.consumeChance != 1) {
						list.add(EmiTooltip.chance("consume", resolve.consumeChance));
					}
					if (node.produceChance != 1) {
						list.add(EmiTooltip.chance("produce", node.produceChance));
					}
				}
				EmiRenderHelper.drawTooltip(screen, context, list, mouseX, mouseY);
				return true;
			} else if (category != null) {
				EmiRenderHelper.drawTooltip(screen, context, category.getTooltip(), mouseX, mouseY);
				return true;
			}
			return false;
		}

		public Hover rememberFocus(Cost cost) {
			// the off variables are bound to the tree, not the camera
			// so we're negating everything here
			double ox = -offX;
			double oy = -offY;
			int cy = nodeHeight * NODE_VERTICAL_SPACING * 2;
			focuser = () -> {
				EmiLog.info("Focusing to Costs");
				offX = -ox;
				offY = -(oy - cy + nodeHeight * NODE_VERTICAL_SPACING * 2);
			};
			return this;
		}

		public Hover rememberFocus(Node node) {
			double ox = -offX;
			double oy = -offY;
			int cy = nodeHeight * NODE_VERTICAL_SPACING * 2;
			focuser = () -> {
				List<EmiStack> chain = new ArrayList<>();
				Node n = node;
				while (n != null) {
					chain.add(n.node.ingredient.getEmiStacks().get(0));
					n = n.parent;
				}
				int index = chain.size() - 1;
				n = null;
				for (Node current : nodes) {
					if (current.parent != n || !current.node.ingredient.getEmiStacks().get(0).isEqual(chain.get(index))) {
						continue;
					}
					index--;
					if (index == -1) {
						EmiLog.info("Focusing to Node");
						offX = -(current.x + (ox - node.x));
						offY = -(current.y + (oy - node.y));
						return;
					}
					n = current;
				}
				EmiLog.info("Fallback focusing to Costs");
				// fallback, same logic as Cost
				offX = -ox;
				offY = -(nodeHeight * NODE_VERTICAL_SPACING * 2 + (oy - cy));
			};
			return this;
		}

		public void bindFocuser(BoMScreen screen) {
			screen.focuser = this.focuser;
		}
	}

	private class Node {
		public Node parent = null;
		public MaterialNode resolution = null;
		public MaterialNode node;
		public int width, x, y, midOffset;
		public long amount;
		public ChanceState chance;
		private CachedText textCache = new CachedText();

		public Node(MaterialNode node, long amount, int x, int y, ChanceState chance) {
			this.node = node;
			if (node.recipe != null) {
				width = 42;
			} else {
				width = 16;
			}
			this.amount = amount;
			this.x = x;
			this.y = y;
			this.chance = chance;
			int tw = EmiRenderHelper.getAmountOverflow(getAmountText());
			width += tw;
			midOffset = tw / -2;
		}

		public void render(EmiDrawContext context, int mouseX, int mouseY, float delta, List<AmountRenderInfo> amounts) {
			Bounds bound = getBoundingBox();
			boolean doRender = camera.overlaps(bound);
			// taking the numbers from below:
			// x - 18 + midOffset, y - 8
			// x + xo - 8 + midOffset, y - 8
			// xo = 0 or 11
			// raw number ranges from -18 to +3, delta 21, meaning width is 16 + 21 = 37
			boolean doStackRender = stackCamera.overlaps(x - 18 + midOffset, y - 8, 37, 16)
					|| !batcher.isPopulated() && batcherCamera.overlaps(x - 18 + midOffset, y - 8, 37, 16);
			if (!doRender && !doStackRender) {
				return;
			}

			if (doRender && parent != null) {
				context.push();

				setColor(context, parent.node, node.consumeChance != 1 || (resolution != null && resolution.consumeChance != 1), false);

				int px = parent.x;
				int py = parent.y;
				int off = NODE_VERTICAL_SPACING - 1;
				if (resolution != null) {
					context.drawTexture(EmiRenderHelper.WIDGETS, x - 3, y - 19, 9, 192, 7, 7);
					drawVerticalLine(context, x, y - 12, y - 11);
					drawVerticalLine(context, x, py + off, y - 19);
				} else {
					drawVerticalLine(context, x, y - 11, py + off);
				}
				setColor(context, parent.node, false, false);
				drawHorizontalLine(context, px, x, py + off);
				context.pop();
			}
			int xo = 0;
			if (node.recipe != null) {
				int lx = x - width / 2;
				int ly = y - 11;
				int hx = x + width / 2;
				int hy = y + 10;
				context.push();

				if (doRender) {
					setColor(context, node, node.produceChance != 1, false);

					if (node.state != FoldState.EXPANDED) {
						drawVerticalLine(context, x, hy + 1, hy + 3);
					} else {
						drawVerticalLine(context, x, hy + 1, hy + 8);
					}
				}

				boolean hovered = mouseX >= lx && mouseY >= ly && mouseX <= hx && mouseY <= hy;
				setColor(context, node, node.produceChance != 1, hovered);
				if (doRender) {
					drawVerticalLine(context, lx, ly, hy);
					drawVerticalLine(context, hx, ly, hy);
					drawHorizontalLine(context, lx, hx, ly);
					drawHorizontalLine(context, lx, hx, hy);
				}
				if (doStackRender) {
					EmiRecipeCategory cat = node.recipe.getCategory();
					if (StackBatcher.isEnabled() && EmiRecipeCategoryProperties.getSimplifiedIcon(cat) instanceof Batchable b) {
						batcher.render(b, context.raw(), x - 18 + midOffset, y - 8, delta);
					} else {
						cat.renderSimplified(context.raw(), x - 18 + midOffset, y - 8, delta);
					}
				}
				xo = 11;
				context.pop();
			}
			context.setColor(1f, 1f, 1f, 1f);
			if (doStackRender) {
				batcher.render(node.ingredient, context.raw(), x + xo - 8 + midOffset, y - 8, 0);
			}
//			EmiRenderHelper.renderAmount(context, x + xo - 8 + midOffset, y - 8, getAmountText());
			// AmountRenderInfo is already culled
			amounts.add(new AmountRenderInfo(x + xo - 8 + midOffset, y - 8, getAmountText()));

			if (EmiConfig.recipeTreeBoundingBoxes) {
				renderBoundingBox(context);
			}
		}

		private void renderBoundingBox(EmiDrawContext context) {
			Bounds bounds = getBoundingBox();
			context.push();

			context.setColor(0.5f,0.5f,0.5f,0.2f);
			int x1 = bounds.x(), y1 = bounds.y(), x2 = this.x, y2 = this.y, temp;
			if (x2 < x1) {
				temp = x1;
				x1 = x2;
				x2 = temp;
			}
			if (y2 < y1) {
				temp = y1;
				y1 = y2;
				y2 = temp;
			}
			context.fill(x1, y1, x2 - x1 + 1, y2 - y1 + 1, -1);
			if (parent != null) {
				int x = (parent.x - this.x) / 2 + this.x;
				int y = (parent.y - this.y) / 2 + this.y;
				context.fill(x - 2, y - 2, 5, 5, -1);
			}

			int color = BOUNDING_BOX_COLORS[boxColorIndex++];
			if (boxColorIndex >= BOUNDING_BOX_COLORS.length) {
				boxColorIndex = 0;
			}
			context.setColor(1, 1, 1);
			context.fill(bounds.left(), bounds.top(), bounds.width(), 1, color);
			context.fill(bounds.left(), bounds.bottom() - 1, bounds.width(), 1, color);
			context.fill(bounds.left(), bounds.top() + 1, 1, bounds.height() - 2, color);
			context.fill(bounds.right() - 1, bounds.top() + 1, 1, bounds.height() - 2, color);
			context.pop();
		}

		private Bounds getBoundingBox() {
			int halfW = this.width / 2;
			int x = this.x - halfW;
			int y = this.y - 11;
			int w = this.width | 1; // halfW * 2 + 1
			int h = 22; // (y + 10) - (y - 11) + 1
			if (parent != null) {
				int dx = parent.x - this.x;
				if (dx > halfW) {
					w += dx - halfW;
				} else if (dx < -halfW) {
					dx += halfW;
					x += dx;
					w += -dx;
				}
				// -dy = y - (parent.y + (NODE_VERTICAL_SPACING - 1))
				// parent.y = centerY - NODE_VERTICAL_SPACING * 2
				// NODE_VERTICAL_SPACING = 20
				// centerY = y + 11
				// y - ((y + 11 - 40) + 19)
				// y - (y - 10)
				// 10
				y -= 10;
				h += 10;
			}
			if (node.recipe != null) {
				h += 8;
			}
			return new Bounds(x - 1, y - 1, w + 2, h + 2);
		}

		public void setColor(EmiDrawContext context, MaterialNode node, boolean chanced, boolean hovered) {
			context.setColor(1f, 1f, 1f, 1f);
			if (chanced) {
				context.setColor(0.8f, 0.6f, 0.1f, 1f);
			}
			if (BoM.craftingMode) {
				if (node.progress == ProgressState.COMPLETED) {
					context.setColor(0.1f, 0.8f, 0.5f, 1f);
				} else if (node.progress == ProgressState.PARTIAL) {
					context.setColor(0.8f, 0.2f, 0.9f, 1f);
				}
			}
			if (hovered) {
				context.setColor(0.5f, 0.6f, 1f, 1f);
			}
		}

		public Text getAmountText() {
			return textCache.getOrCompute(() -> {
				if (chance.chanced()) {
					long a = Math.round(amount * chance.chance());
					a = Math.max(a, node.amount);
					return EmiPort.append(EmiPort.literal("≈"),
							EmiRenderHelper.getAmountText(node.ingredient, a))
						.formatted(Formatting.GOLD);
				} else {
					return EmiRenderHelper.getAmountText(node.ingredient, amount);
				}
			});
		}

		public Hover getHover(int mouseX, int mouseY) {
			if (resolution != null) {
				if (mouseX >= x - 4 && mouseX < x + 4 && mouseY >= y - 19 && mouseY < y - 11) {
					return new Hover(resolution.ingredient, resolution, null);
				}
			}
			int imx = mouseX;
			if (node.recipe != null) {
				if (mouseX >= x - 18 + midOffset && mouseX < x - 2 + midOffset && mouseY >= y - 8 && mouseY < y + 8) {
					return new Hover(node.recipe.getCategory(), node);
				}
				imx -= 11;
			}
			if (imx >= x - 8 + midOffset && imx < x + 8 + midOffset && mouseY >= y - 8 && mouseY < y + 8) {
				return new Hover(node.ingredient, node, resolution);
			}
			int lx = x - width / 2;
			int ly = y - 11;
			int hx = x + width / 2;
			int hy = y + 10;
			if (mouseX >= lx && mouseY >= ly && mouseX <= hx && mouseY <= hy) {
				return new Hover(node);
			}
			return null;
		}
	}

	private class TreeVolume {
		public List<Width> widths = Lists.newArrayList();
		public List<Node> nodes = Lists.newArrayList();

		public TreeVolume(MaterialNode node, long amount, int y, ChanceState chance) {
			Node head = new Node(node, amount, 0, y, chance);
			int l = head.width / 2;
			widths.add(new Width(-l, head.width - l));
			nodes.add(head);
		}

		public void addHead(MaterialNode node, long amount, int y, ChanceState chance) {
			int x = (getLeft(0) + getRight(0)) / 2;
			Node newNode = new Node(node, amount, x, y, chance);
			for (Node n : nodes) {
				if (n.parent == null) {
					n.parent = newNode;
				}
				n.y += NODE_VERTICAL_SPACING;
			}
			int l = newNode.width / 2;
			widths.add(0, new Width(x - l, x + newNode.width - l));
			nodes.add(0, newNode);
		}

		public int getDepth() {
			return widths.size();
		}

		public int getMinLeft() {
			int m = getLeft(0);
			for (int i = 1; i < getDepth(); i++) {
				m = Math.min(m, getLeft(i));
			}
			return m;
		}

		public int getMaxRight() {
			int m = getRight(0);
			for (int i = 1; i < getDepth(); i++) {
				m = Math.max(m, getRight(i));
			}
			return m;
		}

		public int getLeft(int depth) {
			return widths.get(depth).left;
		}

		public int getRight(int depth) {
			return widths.get(depth).right;
		}

		public void addToRight(TreeVolume other) {
			int rOff = getRight(0) - other.getLeft(0) + NODE_HORIZONTAL_SPACING;
			for (int i = 1; i < getDepth() && i < other.getDepth(); i++) {
				rOff = Math.max(rOff, getRight(i) - other.getLeft(i) + NODE_HORIZONTAL_SPACING);
			}
			for (int i = 0; i < other.getDepth(); i++) {
				if (i < getDepth()) {
					widths.get(i).right = other.getRight(i) + rOff;
				} else {
					widths.add(new Width(other.getLeft(i) + rOff, other.getRight(i) + rOff));
				}
			}
			for (Node node : other.nodes) {
				node.x += rOff;
				nodes.add(node);
			}
		}

		private static class Width {
			private int left, right;

			public Width(int left, int right) {
				this.left = left;
				this.right = right;
			}
		}
	}

	private static record AmountRenderInfo(int x, int y, Text amount) {
		public void render(EmiDrawContext context, Bounds camera) {
			int width = EmiRenderHelper.getTextWidth(amount);
			int tx = x - Math.min(EmiRenderHelper.AMOUNT_TEXT_MAX_LEFT_SHIFT, width);
			if (camera.overlaps(tx, y, width + 1, 10)) {
				context.drawTextWithShadow(amount, tx, y, -1);
			}
		}
	}

	private static class CachedText {
		private static int mainSyncId = 1;
		private int syncId = 0;
		private Text cache = null;
		private int width = 0;

		public static void invalidate() {
			mainSyncId++;
		}

		public Text getOrCompute(Supplier<Text> supplier) {
			if (syncId != mainSyncId || cache == null) {
				cache = supplier.get();
				syncId = mainSyncId;
				width = EmiRenderHelper.getTextWidth(cache);
			}
			return cache;
		}

		/**
		 * Does not check if it's up-to-date. Consider trigger the getter first.
		 */
		public int getWidth() {
			return this.width;
		}
	}
}
