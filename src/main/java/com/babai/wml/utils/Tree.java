package com.babai.wml.utils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A generic tree built with an explicit cursor stack instead of ids or
 * node handles.
 *
 * <p>{@code add(value)} always inserts under the current cursor position.
 * {@code descend()} moves the cursor into the child just added; {@code
 * ascend()} moves it back out to the parent. Both are ordinary method
 * calls sitting right there in your code — unlike an implicit "current
 * node" field that changes underneath you, the cursor only ever moves at
 * a {@code descend()}/{@code ascend()} call site, so it's always visible
 * where and when it moves:
 *
 * <pre>{@code
 * Tree<String> t = new Tree<>();
 * t.add("project");
 * t.descend();
 *     t.add("src");
 *     t.descend();
 *         t.add("main.py");
 *     t.ascend();
 *     t.add("tests");
 *     t.descend();
 *         t.add("test_main.py");
 *     t.ascend();
 * t.ascend();
 * }</pre>
 *
 * Indenting to match nesting (as above) is just a convention — it mirrors
 * the shape of the tree in the source, which is especially natural when
 * the calls line up with recursive descent (e.g. walking a directory
 * tree and calling {@code descend()}/{@code ascend()} around each
 * recursive call).
 *
 * <p>{@link #toString()} renders exactly like the Unix {@code tree}
 * command (box-drawing branches, no color):
 *
 * <pre>
 * project
 * ├── src
 * │   └── main.py
 * └── tests
 *     └── test_main.py
 * </pre>
 *
 * @param <T> the type of value stored in each node
 */

@AIGenerated
public class Tree<T> {

    private static final class Node<T> {
        final T value;
        final List<Node<T>> children = new ArrayList<>();

        Node(T value) {
            this.value = value;
        }
    }

    // Path from root (bottom) to the node currently "entered" (top).
    // Empty until descend() is called for the first time.
    private final Deque<Node<T>> cursor = new ArrayDeque<>();
    // The node the most recent add() produced at the current level —
    // the target descend() will enter, and what ascend() restores this
    // pointer to when it leaves a level.
    private Node<T> lastAdded;
    private Node<T> root;

    public Tree() {
    }

    public Tree(T rootValue) {
        add(rootValue);
    }

    /**
     * Adds {@code value} as a new child of whichever node is currently
     * entered (see {@link #descend()}). If the tree is empty, this
     * instead creates the root itself — call {@link #descend()}
     * afterward to enter it before adding its children.
     *
     * @throws IllegalStateException if the root already exists but hasn't
     *                                been entered with {@link #descend()} yet
     */
    public void add(T value) {
        if (root == null) {
            root = new Node<>(value);
            lastAdded = root;
            return;
        }
        Node<T> current = cursor.peek();
        if (current == null) {
            throw new IllegalStateException("call descend() to enter the root before adding to it");
        }
        Node<T> child = new Node<>(value);
        current.children.add(child);
        lastAdded = child;
    }

    /**
     * Enters the node most recently produced by {@link #add()} at this
     * level, so subsequent {@code add()} calls become its children.
     *
     * @throws IllegalStateException if there is nothing to descend into
     *                                (no root yet, or nothing added since
     *                                the last descend)
     */
    public void descend() {
        if (lastAdded == null) {
            throw new IllegalStateException("call add() first; nothing to descend into");
        }
        cursor.push(lastAdded);
        lastAdded = null;
    }

    /**
     * Leaves the current node, returning to its parent (or, if currently
     * inside the root, leaving the tree entirely — a subsequent
     * {@code add()} would then be rejected until something is descended
     * into again). Further {@code add()} calls become siblings of the
     * node just left.
     *
     * @throws IllegalStateException if not currently inside any node —
     *                                i.e. this ascend() has no matching
     *                                descend() anywhere on the call stack
     */
    public void ascend() {
        if (cursor.isEmpty()) {
            throw new IllegalStateException(
                "not inside any node; this ascend() has no matching descend()");
        }
        lastAdded = cursor.pop();
    }

    /** The value of the node currently entered. */
    public T current() {
        Node<T> c = cursor.peek();
        if (c == null) {
            throw new IllegalStateException("not inside any node; call descend() first");
        }
        return c.value;
    }

    /** How many levels below the root the cursor has descended (0 = not entered yet). */
    public int depth() {
        return cursor.size();
    }

    public boolean isEmpty() {
        return root == null;
    }

    /** Total number of nodes in the tree (0 if empty). */
    public int size() {
        return root == null ? 0 : countSubtree(root);
    }

    private int countSubtree(Node<T> node) {
        int count = 1;
        for (Node<T> c : node.children) {
            count += countSubtree(c);
        }
        return count;
    }

    /**
     * Renders the whole tree in the style of the Unix {@code tree} command
     * (no color), from the root regardless of where the cursor currently
     * is. Empty tree renders as an empty string.
     */
    @Override
    public String toString() {
        return root == null ? "" : render(root);
    }

    private String render(Node<T> start) {
        StringBuilder sb = new StringBuilder();
        sb.append(start.value).append('\n');
        appendChildren(sb, start, "");
        return sb.toString();
    }

    private void appendChildren(StringBuilder sb, Node<T> node, String prefix) {
        int n = node.children.size();
        for (int i = 0; i < n; i++) {
            Node<T> child = node.children.get(i);
            boolean last = (i == n - 1);
            sb.append(prefix)
              .append(last ? "\u2514\u2500\u2500 " : "\u251C\u2500\u2500 ")
              .append(child.value)
              .append('\n');
            appendChildren(sb, child, prefix + (last ? "    " : "\u2502   "));
        }
    }
}