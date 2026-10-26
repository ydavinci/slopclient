package dev.slopclient.anvil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the cheapest order to combine enchanted books onto an item in an anvil.
 *
 * <p>Vanilla's rules (AnvilMenu): a step costs the prior-work penalty of both inputs plus,
 * for every enchantment on the right input, its anvil cost (halved, at least 1, when the
 * right input is a book) times the resulting level. Afterwards the penalty becomes
 * {@code 2 * max(left, right) + 1}. A step of 40 levels or more is "Too Expensive".
 *
 * <p>The item always stays on the left. Since the cost of merging into a set of books only
 * depends on which books are in it and its penalty, a dynamic programme over (set of
 * books, penalty) is exact: first for every set merged into one book, then for the item
 * taking in one such book after the other. Steps are weighed by experience points (big
 * steps cost more per level), ties by levels.
 *
 * <p>Plain Java, no Minecraft types, so it can be checked on its own.
 */
public final class AnvilPlanner {
	/** Survival limit: a step must cost less than this. */
	public static final int TOO_EXPENSIVE = 40;
	/** More books than this would make the search slow; no vanilla item gets near it. */
	public static final int MAX_BOOKS = 12;

	/** One book to apply: what it says and what it costs as the right input (already book-halved). */
	public record Book(String label, int value) {
	}

	/**
	 * One anvil step: `left` + `right` for `levels`; `item` when the left input is the item.
	 * Merged books are called "Buch A", "Buch B" and so on; `result` names the new one.
	 */
	public record Step(String left, String right, String result, int levels, boolean item) {
	}

	/** The plan: steps in order, totals, and whether every step stays under the limit. */
	public record Plan(List<Step> steps, int levels, long xp, boolean possible) {
	}

	private record Node(long xp, int levels, int maxStep, int leftMask, int leftPenalty, int rightMask, int rightPenalty) {
	}

	private AnvilPlanner() {
	}

	/**
	 * Plans `books` onto an item called `item` whose prior-work penalty is `itemPenalty`
	 * (its repair cost component). Null for no books. Prefers plans where every step is
	 * possible in survival; falls back to the cheapest overall with `possible == false`.
	 */
	public static Plan plan(String item, int itemPenalty, List<Book> books) {
		if (books.isEmpty()) return null;
		if (books.size() > MAX_BOOKS) throw new IllegalArgumentException("too many books: " + books.size());
		Plan plan = plan(item, itemPenalty, books, true);
		return plan != null ? plan : plan(item, itemPenalty, books, false);
	}

	private static Plan plan(String item, int itemPenalty, List<Book> books, boolean limited) {
		int n = books.size(), full = (1 << n) - 1;
		int[] value = new int[full + 1];
		for (int mask = 1; mask <= full; mask++) {
			int low = Integer.numberOfTrailingZeros(mask);
			value[mask] = value[mask & (mask - 1)] + books.get(low).value();
		}

		// Every set of books merged into a single book, by penalty.
		@SuppressWarnings("unchecked")
		Map<Integer, Node>[] merged = new Map[full + 1];
		for (int mask = 1; mask <= full; mask++) {
			merged[mask] = new HashMap<>();
			if (Integer.bitCount(mask) == 1) {
				merged[mask].put(0, new Node(0, 0, 0, 0, 0, 0, 0));
				continue;
			}
			for (int left = (mask - 1) & mask; left > 0; left = (left - 1) & mask) {
				int right = mask ^ left;
				for (var a : merged[left].entrySet()) {
					for (var b : merged[right].entrySet()) {
						int step = value[right] + a.getKey() + b.getKey();
						if (limited && step >= TOO_EXPENSIVE) continue;
						int penalty = 2 * Math.max(a.getKey(), b.getKey()) + 1;
						keep(merged[mask], penalty, a.getValue(), b.getValue(), step, left, a.getKey(), right, b.getKey());
					}
				}
			}
		}

		// The item taking in merged books, one after another.
		@SuppressWarnings("unchecked")
		Map<Integer, Node>[] onItem = new Map[full + 1];
		onItem[0] = new HashMap<>(Map.of(itemPenalty, new Node(0, 0, 0, 0, 0, 0, 0)));
		for (int mask = 0; mask < full; mask++) {
			if (onItem[mask] == null) continue;
			int rest = full ^ mask;
			for (int add = rest; add > 0; add = (add - 1) & rest) {
				for (var a : onItem[mask].entrySet()) {
					for (var b : merged[add].entrySet()) {
						int step = value[add] + a.getKey() + b.getKey();
						if (limited && step >= TOO_EXPENSIVE) continue;
						int penalty = 2 * Math.max(a.getKey(), b.getKey()) + 1;
						if (onItem[mask | add] == null) onItem[mask | add] = new HashMap<>();
						keep(onItem[mask | add], penalty, a.getValue(), b.getValue(), step, mask, a.getKey(), add, b.getKey());
					}
				}
			}
		}
		if (onItem[full] == null || onItem[full].isEmpty()) return null;

		var best = onItem[full].values().stream().min(AnvilPlanner::compare).orElseThrow();
		List<Step> steps = new ArrayList<>();
		itemSteps(item, books, merged, onItem, full, penaltyOf(onItem[full], best), steps, new int[1]);
		return new Plan(steps, best.levels(), best.xp(), best.maxStep() < TOO_EXPENSIVE);
	}

	private static void keep(Map<Integer, Node> into, int penalty, Node a, Node b, int step, int leftMask, int leftPenalty, int rightMask, int rightPenalty) {
		Node node = new Node(a.xp() + b.xp() + xp(step), a.levels() + b.levels() + step,
			Math.max(step, Math.max(a.maxStep(), b.maxStep())), leftMask, leftPenalty, rightMask, rightPenalty);
		Node old = into.get(penalty);
		if (old == null || compare(node, old) < 0) into.put(penalty, node);
	}

	private static int compare(Node a, Node b) {
		if (a.xp() != b.xp()) return Long.compare(a.xp(), b.xp());
		return Integer.compare(a.levels(), b.levels());
	}

	private static int penaltyOf(Map<Integer, Node> map, Node node) {
		for (var e : map.entrySet()) if (e.getValue() == node) return e.getKey();
		throw new IllegalStateException();
	}

	/** Steps that build the item with `mask`; returns nothing, the item's label is `item`. */
	private static void itemSteps(String item, List<Book> books, Map<Integer, Node>[] merged, Map<Integer, Node>[] onItem,
		int mask, int penalty, List<Step> out, int[] named) {
		if (mask == 0) return;
		Node node = onItem[mask].get(penalty);
		itemSteps(item, books, merged, onItem, node.leftMask(), node.leftPenalty(), out, named);
		String book = bookSteps(books, merged, node.rightMask(), node.rightPenalty(), out, named);
		out.add(new Step(item, book, item, stepCost(books, node.rightMask(), node.leftPenalty(), node.rightPenalty()), true));
	}

	/** Steps that merge the books in `mask` into one; returns that book's label. */
	private static String bookSteps(List<Book> books, Map<Integer, Node>[] merged, int mask, int penalty, List<Step> out, int[] named) {
		if (Integer.bitCount(mask) == 1) return books.get(Integer.numberOfTrailingZeros(mask)).label();
		Node node = merged[mask].get(penalty);
		String left = bookSteps(books, merged, node.leftMask(), node.leftPenalty(), out, named);
		String right = bookSteps(books, merged, node.rightMask(), node.rightPenalty(), out, named);
		String result = "Buch " + (char) ('A' + named[0]++);
		out.add(new Step(left, right, result, stepCost(books, node.rightMask(), node.leftPenalty(), node.rightPenalty()), false));
		return result;
	}

	private static int stepCost(List<Book> books, int rightMask, int leftPenalty, int rightPenalty) {
		int cost = leftPenalty + rightPenalty;
		for (int i = 0; i < books.size(); i++) if ((rightMask >> i & 1) != 0) cost += books.get(i).value();
		return cost;
	}

	/** Experience points needed to reach `level` from 0, which is what spending that many levels costs at best. */
	public static long xp(int level) {
		if (level <= 16) return (long) level * level + 6L * level;
		if (level <= 31) return Math.round(2.5 * level * level - 40.5 * level + 360);
		return Math.round(4.5 * level * level - 162.5 * level + 2220);
	}
}
