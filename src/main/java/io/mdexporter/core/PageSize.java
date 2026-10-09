package io.mdexporter.core;

/** Paper sizes for paginated formats (Word, PDF). */
public enum PageSize {
    A4("A4", 210, 297),
    A5("A5", 148, 210),
    LETTER("Letter", 215.9, 279.4),
    LEGAL("Legal", 215.9, 355.6);

    private final String label;
    private final double widthMm;
    private final double heightMm;

    PageSize(String label, double widthMm, double heightMm) {
        this.label = label;
        this.widthMm = widthMm;
        this.heightMm = heightMm;
    }

    public double widthMm() {
        return widthMm;
    }

    public double heightMm() {
        return heightMm;
    }

    /** Width in twentieths of a point (Word unit). */
    public int widthTwips() {
        return (int) Math.round(widthMm / 25.4 * 1440);
    }

    public int heightTwips() {
        return (int) Math.round(heightMm / 25.4 * 1440);
    }

    public static PageSize parse(String value) {
        for (PageSize size : values()) {
            if (size.name().equalsIgnoreCase(value) || size.label.equalsIgnoreCase(value)) {
                return size;
            }
        }
        throw new IllegalArgumentException("Unknown page size: " + value);
    }

    @Override
    public String toString() {
        return label;
    }
}
