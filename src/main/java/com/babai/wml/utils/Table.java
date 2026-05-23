package com.babai.wml.utils;

import java.util.*;

/**
 * Table with Cell-based navigation, automatic indexed lookup, and readable
 * row/column access.
 */
@AIGenerated
public final class Table {
	private final String[] columnNames;
	private final Class<?>[] columnTypes;
	private final List<Row> rows = new ArrayList<>();

	// Indexed columns: columnIndex -> value -> list of Cells
	private final Map<Integer, Map<Object, List<Cell<?>>>> indices = new HashMap<>();

	private Table(Class<?>[] types, String[] names, int... indexedCols) {
		if (types == null || types.length == 0)
			throw new IllegalArgumentException("must provide at least one column type");
		this.columnTypes = types.clone();
		if (names != null) {
			if (names.length != types.length)
				throw new IllegalArgumentException("names length must match types length");
			this.columnNames = names.clone();
		} else {
			this.columnNames = new String[types.length];
			for (int i = 0; i < columnNames.length; i++)
				columnNames[i] = "col" + i;
		}
		for (int col : indexedCols)
			indices.put(col, new HashMap<>());
	}

	public static Table ofWithIndices(Class<?>[] types, String[] names, int... indexedCols) {
		return new Table(types, names, indexedCols);
	}

	public int columnCount() {
		return columnTypes.length;
	}

	public int rowCount() {
		return rows.size();
	}

	/** --- Cell --- */
	public final class Cell<T> {
		private T value;
		private Row parentRow;
		private int colIndex;
		private Cell<?> up, down, left, right;

		private Cell(T value, Row parentRow, int colIndex) {
			this.value = value;
			this.parentRow = parentRow;
			this.colIndex = colIndex;
		}

		public Cell<?> right() {
			return right;
		}

		public Cell<?> left() {
			return left;
		}

		public Cell<?> up() {
			return up;
		}

		public Cell<?> down() {
			return down;
		}

		public T getValue() {
			return value;
		}

		// Accept any object and cast internally
		@SuppressWarnings("unchecked")
		public void setValue(Object v) {
			this.value = (T) v;
			// update index if exists
			Map<Object, List<Cell<?>>> idx = indices.get(colIndex);
			if (idx != null) {
				idx.values().forEach(list -> list.remove(this));
				idx.computeIfAbsent(v, k -> new ArrayList<>()).add(this);
			}
		}
	}

	/** --- Row --- */
	public final class Row {
		private final List<Cell<?>> cells;

		private Row(List<Cell<?>> cells) {
			this.cells = cells;
		}

		public Cell<?> getColumn(String colName) {
			return cells.get(getColumnIndex(colName));
		}

		public List<Cell<?>> cells() {
			return cells;
		}

		@Override
		public String toString() {
			return cells.stream().map(c -> String.valueOf(c.getValue())).toList().toString();
		}
	}

	/** Add a row */
	public void addRow(Object... values) {
		if (values.length != columnCount())
			throw new IllegalArgumentException("Expected " + columnCount() + " values");
		List<Cell<?>> cellList = new ArrayList<>();
		Row row = new Row(cellList);

		for (int i = 0; i < values.length; i++) {
			Object v = values[i];
			if (v != null && !columnTypes[i].isInstance(v))
				throw new ClassCastException("Column " + i + " expects " + columnTypes[i].getName());
			Cell<Object> cell = new Cell<>(v, row, i);

			// link left
			if (!cellList.isEmpty()) {
				Cell<?> left = cellList.get(cellList.size() - 1);
				left.right = cell;
				cell.left = left;
			}

			// link up
			if (!rows.isEmpty()) {
				Cell<?> above = rows.get(rows.size() - 1).cells.get(i);
				above.down = cell;
				cell.up = above;
			}

			cellList.add(cell);

			// update index
			Map<Object, List<Cell<?>>> idx = indices.get(i);
			if (idx != null)
				idx.computeIfAbsent(v, k -> new ArrayList<>()).add(cell);
		}
		rows.add(row);
	}

	/** Get cell by row and column */
	public Cell<?> get(int row, int col) {
		return rows.get(row).cells.get(col);
	}

	private int getColumnIndex(String colName) {
		for (int i = 0; i < columnNames.length; i++)
			if (columnNames[i].equals(colName))
				return i;
		throw new IllegalArgumentException("Unknown column: " + colName);
	}
	
	public List<Object> getColumn(String colName) {
		int cidx = getColumnIndex(colName);
		int rows = rowCount();
		
		List<Object> col = new ArrayList<>();
		for (int i = 0; i < rows; i++) {
			col.add(get(i, cidx).getValue());
		}
		return col;
	}

	/** Fast lookup by indexed column */
	public List<Row> getRows(String colName, Object value) {
		int col = getColumnIndex(colName);
		Map<Object, List<Cell<?>>> idx = indices.get(col);
		if (idx == null)
			throw new IllegalStateException("Column not indexed: " + colName);
		List<Cell<?>> cells = idx.getOrDefault(value, Collections.emptyList());
		List<Row> result = new ArrayList<>();
		for (Cell<?> c : cells)
			result.add(c.parentRow);
		return result;
	}

	/** Fast existence check by indexed column without row list allocation. */
	public boolean hasValue(String colName, Object value) {
		int col = getColumnIndex(colName);
		Map<Object, List<Cell<?>>> idx = indices.get(col);
		if (idx == null)
			throw new IllegalStateException("Column not indexed: " + colName);
		List<Cell<?>> cells = idx.get(value);
		return cells != null && !cells.isEmpty();
	}

	/** Fast first-row lookup by indexed column without materializing all rows. */
	public Row getFirstRow(String colName, Object value) {
		int col = getColumnIndex(colName);
		Map<Object, List<Cell<?>>> idx = indices.get(col);
		if (idx == null)
			throw new IllegalStateException("Column not indexed: " + colName);
		List<Cell<?>> cells = idx.get(value);
		if (cells == null || cells.isEmpty())
			return null;
		return cells.get(0).parentRow;
	}

	public List<Row> getRows() {
		return rows;
	}

	/**
	 * Removes all rows where the given column matches the given value. Example:
	 * definesTable.removeAll("Name", name.image);
	 */
	public void removeAll(String colName, Object value) {
		int col = getColumnIndex(colName);
		Map<Object, List<Cell<?>>> idx = indices.get(col);
		if (idx == null)
			throw new IllegalStateException("Column not indexed: " + colName);

		List<Cell<?>> cells = new ArrayList<>(idx.getOrDefault(value, Collections.emptyList()));
		List<Row> rowsToRemove = new ArrayList<>();
		for (Cell<?> c : cells) {
			rowsToRemove.add(c.parentRow);
		}

		// Remove rows from the main list
		rows.removeAll(rowsToRemove);

		// Remove cells from all indices
		for (Row r : rowsToRemove) {
			for (int i = 0; i < columnCount(); i++) {
				Cell<?> c = r.getColumn(columnNames[i]);
				Map<Object, List<Cell<?>>> colIdx = indices.get(i);
				if (colIdx != null)
					colIdx.getOrDefault(c.getValue(), Collections.emptyList()).remove(c);
			}
		}
	}

	/** Pretty printing */
	@Override
	public String toString() {
		int[] widths = new int[columnCount()];
		for (int i = 0; i < columnCount(); i++)
			widths[i] = columnNames[i].length();
		for (Row r : rows)
			for (int i = 0; i < columnCount(); i++) {
				int len = String.valueOf(r.getColumn(columnNames[i]).getValue()).length();
				if (len > widths[i])
					widths[i] = len;
			}

		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < columnCount(); i++) {
			sb.append(String.format("%-" + widths[i] + "s", columnNames[i]));
			if (i < columnCount() - 1)
				sb.append(" | ");
		}
		sb.append("\n");
		for (int i = 0; i < columnCount(); i++) {
			sb.append("-".repeat(widths[i]));
			if (i < columnCount() - 1)
				sb.append("-+-");
		}
		sb.append("\n");
		for (Row r : rows) {
			for (int i = 0; i < columnCount(); i++) {
				String val = r.getColumn(columnNames[i]).getValue().toString();
				sb.append(String.format("%-" + widths[i] + "s", val));
				if (i < columnCount() - 1)
					sb.append(" | ");
			}
			sb.append("\n");
		}
		return sb.toString();
	}
	
	public Table copy() {
		// find indexed cols
		int[] indexedCols = indices.keySet().stream().mapToInt(Integer::intValue).toArray();
		Table copy = new Table(columnTypes, columnNames, indexedCols);
		for (Row r : rows) {
			Object[] values = r.cells().stream()
					.map(c -> c.getValue())
					.toArray();
			copy.addRow(values);
		}
		return copy;
	}

	/** --- Example --- */
	public static void main(String[] args) {
		Table t = Table.ofWithIndices(new Class<?>[] { Integer.class, String.class, String.class, Double.class },
				new String[] { "ID", "Name", "Other", "Balance" }, 2 // index Name
		);
		t.addRow(1, "Alice", "X", 1000.0);
		t.addRow(2, "Bob", "Y", 500.0);
		t.addRow(3, "Bob", "Z", 700.0);

		System.out.println(t);

		// new readable access
		List<Table.Row> bobs = t.getRows("Name", "Bob");
		for (Table.Row row : bobs) {
			Double balance = (Double) row.getColumn("Balance").getValue();
			System.out.println("Bob balance: " + balance);
		}

		// update
		bobs.get(0).getColumn("Balance").setValue(550.0);
		System.out.println("After update:\n" + t);
	}
}
