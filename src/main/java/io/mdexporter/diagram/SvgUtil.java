package io.mdexporter.diagram;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** String level helpers for SVG markup produced by diagram engines. */
final class SvgUtil {

    private static final Pattern SVG_OPEN = Pattern.compile("<svg\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern VIEW_BOX = Pattern.compile(
            "viewBox\\s*=\\s*\"\\s*([-\\d.eE]+)[\\s,]+([-\\d.eE]+)[\\s,]+([\\d.eE]+)[\\s,]+([\\d.eE]+)\\s*\"");
    private static final Pattern WIDTH = Pattern.compile("\\swidth\\s*=\\s*\"([\\d.]+)(px)?\"");
    private static final Pattern HEIGHT = Pattern.compile("\\sheight\\s*=\\s*\"([\\d.]+)(px)?\"");
    private static final Pattern STYLE = Pattern.compile("\\sstyle\\s*=\\s*\"[^\"]*\"");

    private SvgUtil() {
    }

    /** Removes the XML prolog / doctype so the markup can be inlined in HTML. */
    static String stripProlog(String svg) {
        int start = svg.indexOf("<svg");
        return start > 0 ? svg.substring(start) : svg;
    }

    /** Returns {width, height} from the root element (attributes, else viewBox), or null. */
    static double[] size(String svg) {
        Matcher open = SVG_OPEN.matcher(svg);
        if (!open.find()) {
            return null;
        }
        String tag = open.group();
        Matcher w = WIDTH.matcher(tag);
        Matcher h = HEIGHT.matcher(tag);
        if (w.find() && h.find()) {
            return new double[]{Double.parseDouble(w.group(1)), Double.parseDouble(h.group(1))};
        }
        Matcher vb = VIEW_BOX.matcher(tag);
        if (vb.find()) {
            return new double[]{Double.parseDouble(vb.group(3)), Double.parseDouble(vb.group(4))};
        }
        return null;
    }

    /**
     * Makes the root element responsive: explicit width/height attributes (intrinsic size) and a style that lets
     * CSS shrink it to the container width while keeping the aspect ratio.
     */
    static String normalizeRoot(String svg, double width, double height) {
        Matcher open = SVG_OPEN.matcher(svg);
        if (!open.find()) {
            return svg;
        }
        String tag = open.group();
        String newTag = STYLE.matcher(tag).replaceAll("");
        newTag = WIDTH.matcher(newTag).replaceAll("");
        newTag = HEIGHT.matcher(newTag).replaceAll("");
        if (!VIEW_BOX.matcher(newTag).find()) {
            newTag = newTag.replaceFirst("<svg", "<svg viewBox=\"0 0 " + fmt(width) + " " + fmt(height) + "\"");
        }
        newTag = newTag.replaceFirst("<svg", "<svg width=\"" + fmt(width) + "\" height=\"" + fmt(height)
                + "\" style=\"max-width:100%;height:auto\"");
        return svg.substring(0, open.start()) + newTag + svg.substring(open.end());
    }

    static String fmt(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
