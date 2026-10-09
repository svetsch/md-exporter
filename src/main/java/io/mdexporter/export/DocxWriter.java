package io.mdexporter.export;

import io.mdexporter.core.ImageSupport;
import io.mdexporter.core.MarkdownDocument;
import io.mdexporter.core.Nodes;
import io.mdexporter.core.ResourceLoader;
import io.mdexporter.diagram.RenderedDiagram;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.util.Units;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.TableWidthType;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.VerticalAlign;
import org.apache.poi.xwpf.usermodel.XWPFAbstractFootnoteEndnote;
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun;
import org.apache.poi.xwpf.usermodel.XWPFNumbering;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRelation;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.xmlbeans.impl.xb.xmlschema.SpaceAttribute;
import org.commonmark.ext.footnotes.FootnoteDefinition;
import org.commonmark.ext.footnotes.FootnoteReference;
import org.commonmark.ext.footnotes.InlineFootnote;
import org.commonmark.ext.front.matter.YamlFrontMatterBlock;
import org.commonmark.ext.gfm.alerts.Alert;
import org.commonmark.ext.gfm.alerts.AlertsExtension;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.heading.anchor.IdGenerator;
import org.commonmark.ext.image.attributes.ImageAttributes;
import org.commonmark.ext.ins.Ins;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.Link;
import org.commonmark.node.ListBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTInline;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBookmark;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTHyperlink;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTInd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTMarkupRange;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTNumLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPBdr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSimpleField;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyles;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblWidth;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTText;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STJc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMultiLevelType;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STPageOrientation;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblWidth;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.StylesDocument;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;

/** Walks the CommonMark AST and builds the Word document. */
final class DocxWriter {

    private static final Logger LOG = Logger.getLogger(DocxWriter.class.getName());

    private static final int LIST_INDENT = 420;      // twips per nesting level
    private static final int LIST_HANGING = 300;
    private static final int QUOTE_INDENT = 300;
    private static final String SYMBOL_FONT = "Segoe UI Symbol";
    private static final String CODE_FONT = "Consolas";
    private static final String LINK_COLOR = "0969DA";

    private static final Map<String, String> ALERT_COLORS = Map.of(
            "NOTE", "0969DA", "TIP", "1A7F37", "IMPORTANT", "8250DF", "WARNING", "9A6700", "CAUTION", "D1242F");

    // ------------------------------------------------------------------ containers

    /** Something paragraphs and tables can be appended to (document body, table cell, footnote). */
    private interface Body {
        XWPFParagraph paragraph();

        XWPFTable table(int rows, int cols);

        default boolean isDocument() {
            return false;
        }
    }

    private final class DocumentBody implements Body {
        @Override
        public XWPFParagraph paragraph() {
            return doc.createParagraph();
        }

        @Override
        public XWPFTable table(int rows, int cols) {
            return doc.createTable(rows, cols);
        }

        @Override
        public boolean isDocument() {
            return true;
        }
    }

    /** Reuses the initial empty paragraph of a freshly created cell / footnote. */
    private static final class ReusingBody implements Body {
        private final Supplier<XWPFParagraph> create;
        private final java.util.function.BiFunction<Integer, Integer, XWPFTable> createTable;
        private XWPFParagraph initial;

        ReusingBody(XWPFParagraph initial, Supplier<XWPFParagraph> create,
                    java.util.function.BiFunction<Integer, Integer, XWPFTable> createTable) {
            this.initial = initial;
            this.create = create;
            this.createTable = createTable;
        }

        @Override
        public XWPFParagraph paragraph() {
            if (initial != null) {
                XWPFParagraph p = initial;
                initial = null;
                return p;
            }
            return create.get();
        }

        @Override
        public XWPFTable table(int rows, int cols) {
            if (createTable == null) {
                return null;
            }
            initial = null;
            return createTable.apply(rows, cols);
        }
    }

    /** Block level rendering state. */
    private record Ctx(Body body, int indent, int quoteDepth, String quoteColor, boolean tight,
                       PendingMarker marker, boolean footnote) {
        Ctx withBody(Body b) {
            return new Ctx(b, indent, quoteDepth, quoteColor, tight, null, footnote);
        }

        Ctx quote(String color) {
            return new Ctx(body, indent + QUOTE_INDENT, quoteDepth + 1, color, false, null, footnote);
        }

        Ctx list(int newIndent, boolean isTight, PendingMarker m) {
            return new Ctx(body, newIndent, quoteDepth, quoteColor, isTight, m, footnote);
        }

        Ctx withoutMarker() {
            return marker == null ? this : new Ctx(body, indent, quoteDepth, quoteColor, tight, null, footnote);
        }
    }

    /** List numbering (or task checkbox) waiting to be applied to the first paragraph of a list item. */
    private static final class PendingMarker {
        final BigInteger numId;
        final int level;
        final Boolean task;
        boolean used;

        PendingMarker(BigInteger numId, int level, Boolean task) {
            this.numId = numId;
            this.level = level;
            this.task = task;
        }
    }

    /** Character formatting while rendering inlines. */
    private static final class Fmt {
        boolean bold, italic, strike, underline, code, sup, sub, link;
        String color;

        Fmt copy() {
            Fmt f = new Fmt();
            f.bold = bold;
            f.italic = italic;
            f.strike = strike;
            f.underline = underline;
            f.code = code;
            f.sup = sup;
            f.sub = sub;
            f.link = link;
            f.color = color;
            return f;
        }
    }

    // ------------------------------------------------------------------ state

    private final XWPFDocument doc;
    private final MarkdownDocument md;
    private final ExportContext ctx;
    private final IdGenerator headingIds = IdGenerator.builder().build();
    private final Map<String, FootnoteDefinition> footnoteDefinitions = new HashMap<>();
    private final Map<String, String> externalRelations = new HashMap<>();
    private final int contentWidthTwips;
    private final int contentHeightTwips;
    private BigInteger bulletAbstractId;
    private BigInteger orderedAbstractId;
    private BigInteger bulletNumId;
    private XWPFNumbering numbering;
    private int bookmarkCounter;
    private int imageCounter;

    DocxWriter(XWPFDocument doc, MarkdownDocument md, ExportContext ctx) {
        this.doc = doc;
        this.md = md;
        this.ctx = ctx;
        double marginMm = ctx.options().getMarginMm();
        this.contentWidthTwips = mmToTwips(ctx.options().pageWidthMm() - 2 * marginMm);
        this.contentHeightTwips = mmToTwips(ctx.options().pageHeightMm() - 2 * marginMm);
    }

    void write() throws Exception {
        installStyles();
        installNumbering();
        collectFootnotes();
        setProperties();

        Ctx root = new Ctx(new DocumentBody(), 0, 0, null, false, null, false);
        writeTitleBlock(root);
        if (ctx.options().isTableOfContents()) {
            writeToc(root);
        }
        List<Node> blocks = Nodes.children(md.root());
        for (int i = 0; i < blocks.size(); i++) {
            renderBlock(blocks.get(i), root);
            separateIfNeeded(blocks.get(i), root);
            ctx.progress().update(-1, "Writing Word document (" + (i + 1) + "/" + blocks.size() + ")");
        }
        setupPage();
    }

    // ------------------------------------------------------------------ document setup

    private void installStyles() throws Exception {
        try (InputStream in = DocxWriter.class.getResourceAsStream("/io/mdexporter/docx/styles.xml")) {
            if (in == null) {
                throw new IllegalStateException("Missing docx/styles.xml");
            }
            CTStyles styles = StylesDocument.Factory.parse(in).getStyles();
            XWPFStyles xwpfStyles = doc.createStyles();
            xwpfStyles.setStyles(styles);
        }
    }

    private void installNumbering() {
        numbering = doc.createNumbering();
        CTAbstractNum bullet = CTAbstractNum.Factory.newInstance();
        bullet.setAbstractNumId(BigInteger.ZERO);
        bullet.addNewMultiLevelType().setVal(STMultiLevelType.HYBRID_MULTILEVEL);
        String[] bullets = {"\u2022", "\u25E6", "\u25AA"};
        for (int i = 0; i < 9; i++) {
            CTLvl lvl = bullet.addNewLvl();
            lvl.setIlvl(BigInteger.valueOf(i));
            lvl.addNewStart().setVal(BigInteger.ONE);
            lvl.addNewNumFmt().setVal(STNumberFormat.BULLET);
            lvl.addNewLvlText().setVal(bullets[i % bullets.length]);
            lvl.addNewLvlJc().setVal(STJc.LEFT);
            CTInd ind = lvl.addNewPPr().addNewInd();
            ind.setLeft(BigInteger.valueOf((long) LIST_INDENT * (i + 1)));
            ind.setHanging(BigInteger.valueOf(LIST_HANGING));
            var fonts = lvl.addNewRPr().addNewRFonts();
            fonts.setAscii("Arial");
            fonts.setHAnsi("Arial");
        }
        bulletAbstractId = numbering.addAbstractNum(new XWPFAbstractNum(bullet, numbering));

        CTAbstractNum ordered = CTAbstractNum.Factory.newInstance();
        ordered.setAbstractNumId(BigInteger.ONE);
        ordered.addNewMultiLevelType().setVal(STMultiLevelType.HYBRID_MULTILEVEL);
        for (int i = 0; i < 9; i++) { // decimal on every level, like HTML ordered lists
            CTLvl lvl = ordered.addNewLvl();
            lvl.setIlvl(BigInteger.valueOf(i));
            lvl.addNewStart().setVal(BigInteger.ONE);
            lvl.addNewNumFmt().setVal(STNumberFormat.DECIMAL);
            lvl.addNewLvlText().setVal("%" + (i + 1) + ".");
            lvl.addNewLvlJc().setVal(STJc.LEFT);
            CTInd ind = lvl.addNewPPr().addNewInd();
            ind.setLeft(BigInteger.valueOf((long) LIST_INDENT * (i + 1)));
            ind.setHanging(BigInteger.valueOf(LIST_HANGING));
        }
        orderedAbstractId = numbering.addAbstractNum(new XWPFAbstractNum(ordered, numbering));
        bulletNumId = numbering.addNum(bulletAbstractId);
    }

    private BigInteger newOrderedNum(int level, int start) {
        BigInteger numId = numbering.addNum(orderedAbstractId);
        CTNumLvl override = numbering.getNum(numId).getCTNum().addNewLvlOverride();
        override.setIlvl(BigInteger.valueOf(level));
        override.addNewStartOverride().setVal(BigInteger.valueOf(Math.max(0, start)));
        return numId;
    }

    private void collectFootnotes() {
        md.root().accept(new AbstractVisitor() {
            @Override
            public void visit(org.commonmark.node.CustomBlock block) {
                if (block instanceof FootnoteDefinition def) {
                    footnoteDefinitions.putIfAbsent(def.getLabel(), def);
                }
                visitChildren(block);
            }
        });
    }

    private void setProperties() {
        var core = doc.getProperties().getCoreProperties();
        core.setTitle(md.title());
        String author = md.frontMatterValue("author");
        core.setCreator(author != null ? author : "md-exporter");
        String description = md.frontMatterValue("description");
        if (description != null) {
            core.setDescription(description);
        }
        String keywords = md.frontMatterValue("keywords");
        if (keywords != null) {
            core.setKeywords(keywords);
        }
    }

    private void setupPage() {
        CTSectPr sect = doc.getDocument().getBody().isSetSectPr()
                ? doc.getDocument().getBody().getSectPr()
                : doc.getDocument().getBody().addNewSectPr();
        boolean landscape = ctx.options().getOrientation() == io.mdexporter.core.ExportOptions.Orientation.LANDSCAPE;
        CTPageSz size = sect.isSetPgSz() ? sect.getPgSz() : sect.addNewPgSz();
        size.setW(BigInteger.valueOf(mmToTwips(ctx.options().pageWidthMm())));
        size.setH(BigInteger.valueOf(mmToTwips(ctx.options().pageHeightMm())));
        if (landscape) {
            size.setOrient(STPageOrientation.LANDSCAPE);
        }
        CTPageMar margin = sect.isSetPgMar() ? sect.getPgMar() : sect.addNewPgMar();
        BigInteger m = BigInteger.valueOf(mmToTwips(ctx.options().getMarginMm()));
        margin.setTop(m);
        margin.setBottom(m);
        margin.setLeft(m);
        margin.setRight(m);
        margin.setHeader(BigInteger.valueOf(500));
        margin.setFooter(BigInteger.valueOf(500));
        margin.setGutter(BigInteger.ZERO);

        XWPFFooter footer = doc.createFooter(HeaderFooterType.DEFAULT);
        XWPFParagraph p = footer.getParagraphs().isEmpty() ? footer.createParagraph() : footer.getParagraphs().get(0);
        p.setAlignment(ParagraphAlignment.CENTER);
        CTSimpleField field = p.getCTP().addNewFldSimple();
        field.setInstr("PAGE \\* MERGEFORMAT");
        CTR r = field.addNewR();
        r.addNewRPr().addNewColor().setVal("6E7781");
        r.addNewT().setStringValue("1");
    }

    private void writeTitleBlock(Ctx c) {
        String title = md.frontMatterValue("title");
        if (title == null) {
            return;
        }
        XWPFParagraph p = c.body().paragraph();
        p.setStyle("Title");
        p.createRun().setText(title);
        String subtitle = md.frontMatterValue("subtitle");
        String author = md.frontMatterValue("author");
        String date = md.frontMatterValue("date");
        String meta = String.join(" \u00B7 ", java.util.stream.Stream.of(author, date)
                .filter(s -> s != null && !s.isBlank()).toList());
        if (subtitle != null) {
            XWPFParagraph s = c.body().paragraph();
            s.setStyle("Subtitle");
            s.createRun().setText(subtitle);
        }
        if (!meta.isEmpty()) {
            XWPFParagraph s = c.body().paragraph();
            s.setStyle("Subtitle");
            XWPFRun run = s.createRun();
            run.setText(meta);
            run.setFontSize(11);
        }
    }

    private void writeToc(Ctx c) {
        XWPFParagraph heading = c.body().paragraph();
        heading.setStyle("TOCHeading");
        heading.createRun().setText("Contents");

        XWPFParagraph p = c.body().paragraph();
        CTP ctp = p.getCTP();
        CTR begin = ctp.addNewR();
        begin.addNewFldChar().setFldCharType(STFldCharType.BEGIN);
        begin.getFldCharArray(0).setDirty(true);
        CTR instr = ctp.addNewR();
        CTText text = instr.addNewInstrText();
        text.setStringValue(" TOC \\o \"1-3\" \\h \\z \\u ");
        text.setSpace(SpaceAttribute.Space.PRESERVE);
        ctp.addNewR().addNewFldChar().setFldCharType(STFldCharType.SEPARATE);
        CTR placeholder = ctp.addNewR();
        placeholder.addNewRPr().addNewColor().setVal("59636E");
        placeholder.addNewT().setStringValue("Right-click and choose \u201CUpdate Field\u201D to refresh the table of contents.");
        ctp.addNewR().addNewFldChar().setFldCharType(STFldCharType.END);
        p.setPageBreak(false);

        XWPFParagraph pageBreak = c.body().paragraph();
        pageBreak.createRun().addBreak(org.apache.poi.xwpf.usermodel.BreakType.PAGE);
        doc.enforceUpdateFields();
    }

    // ------------------------------------------------------------------ blocks

    private void renderChildren(Node parent, Ctx c) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            renderBlock(child, c);
            separateIfNeeded(child, c);
        }
    }

    /**
     * Word merges adjacent paragraphs having identical borders into one box: keep consecutive quotes, alerts and
     * code blocks apart with a tiny empty paragraph.
     */
    private void separateIfNeeded(Node node, Ctx c) {
        if (c.quoteDepth() == 0 && isBordered(node) && isBordered(node.getNext())) {
            XWPFParagraph spacer = c.body().paragraph();
            spacer.setSpacingBefore(0);
            spacer.setSpacingAfter(0);
            spacer.createRun().setFontSize(4);
        }
    }

    private boolean isBordered(Node node) {
        return node instanceof BlockQuote || node instanceof Alert || node instanceof IndentedCodeBlock
                || (node instanceof FencedCodeBlock code && !ctx.isDiagram(code));
    }

    private void renderBlock(Node node, Ctx c) {
        if (node instanceof Heading h) {
            renderHeading(h, c);
        } else if (node instanceof Paragraph p) {
            renderParagraph(p, c);
        } else if (node instanceof BulletList || node instanceof OrderedList) {
            renderList((ListBlock) node, c);
        } else if (node instanceof Alert alert) {
            renderAlert(alert, c);
        } else if (node instanceof BlockQuote q) {
            renderChildren(q, c.withoutMarker().quote(null));
        } else if (node instanceof FencedCodeBlock code) {
            if (ctx.isDiagram(code)) {
                renderDiagram(code, c);
            } else {
                renderCode(code.getLiteral(), c);
            }
        } else if (node instanceof IndentedCodeBlock code) {
            renderCode(code.getLiteral(), c);
        } else if (node instanceof TableBlock table) {
            renderTable(table, c);
        } else if (node instanceof ThematicBreak) {
            renderRule(c);
        } else if (node instanceof HtmlBlock html) {
            renderHtmlBlock(html, c);
        } else if (node instanceof FootnoteDefinition || node instanceof YamlFrontMatterBlock
                || node instanceof LinkReferenceDefinition) {
            // footnotes are rendered at their reference; front matter in the title block
        } else {
            renderChildren(node, c);
        }
    }

    /** Creates a paragraph honouring list markers, indentation and quote styling. */
    private XWPFParagraph newParagraph(Ctx c, String style) {
        XWPFParagraph p = c.body().paragraph();
        if (style != null) {
            p.setStyle(style);
        } else if (c.quoteDepth() > 0) {
            p.setStyle("Quote");
        } else if (c.footnote()) {
            p.setStyle("FootnoteText");
        }
        PendingMarker marker = c.marker();
        boolean numbered = false;
        if (marker != null && !marker.used) {
            marker.used = true;
            if (marker.task == null) {
                p.setNumID(marker.numId);
                p.setNumILvl(BigInteger.valueOf(marker.level));
                numbered = true;
            } else {
                XWPFRun box = p.createRun();
                box.setText(marker.task ? "\u2611 " : "\u2610 ");
                box.setFontFamily(SYMBOL_FONT);
                numbered = true;
            }
        }
        if (c.indent() > 0) {
            CTPPr ppr = p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
            CTInd ind = ppr.isSetInd() ? ppr.getInd() : ppr.addNewInd();
            ind.setLeft(BigInteger.valueOf(c.indent()));
            if (numbered) {
                ind.setHanging(BigInteger.valueOf(LIST_HANGING));
            }
        }
        if (c.quoteDepth() > 0 && c.quoteColor() != null) {
            CTPPr ppr = p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
            CTPBdr bdr = ppr.isSetPBdr() ? ppr.getPBdr() : ppr.addNewPBdr();
            CTBorder left = bdr.isSetLeft() ? bdr.getLeft() : bdr.addNewLeft();
            left.setVal(STBorder.SINGLE);
            left.setSz(BigInteger.valueOf(24));
            left.setSpace(BigInteger.valueOf(8));
            left.setColor(c.quoteColor());
        }
        if (c.tight()) {
            p.setSpacingAfter(40);
        } else if (c.marker() != null || c.indent() > 0 && c.quoteDepth() == 0) {
            p.setSpacingAfter(100);
        }
        return p;
    }

    private void renderHeading(Heading h, Ctx c) {
        int level = Math.max(1, Math.min(6, h.getLevel()));
        XWPFParagraph p = newParagraph(c, c.footnote() ? null : "Heading" + level);
        String id = headingIds.generateId(Nodes.textContent(h).trim());
        BigInteger bmId = BigInteger.valueOf(++bookmarkCounter);
        CTBookmark start = p.getCTP().addNewBookmarkStart();
        start.setId(bmId);
        start.setName(bookmarkName(id));
        renderInlines(h, p, runSink(p), new Fmt());
        CTMarkupRange end = p.getCTP().addNewBookmarkEnd();
        end.setId(bmId);
    }

    private void renderParagraph(Paragraph para, Ctx c) {
        XWPFParagraph p = newParagraph(c, null);
        renderInlines(para, p, runSink(p), new Fmt());
    }

    private void renderList(ListBlock list, Ctx c) {
        int level = c.marker() != null ? c.marker().level + 1 : depthOf(list);
        boolean ordered = list instanceof OrderedList;
        BigInteger numId = ordered ? newOrderedNum(level, ((OrderedList) list).getMarkerStartNumber() == null
                ? 1 : ((OrderedList) list).getMarkerStartNumber()) : bulletNumId;
        int indent = c.indent() + LIST_INDENT;
        for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
            if (!(item instanceof ListItem li)) {
                continue;
            }
            Boolean task = null;
            Node first = li.getFirstChild();
            if (first instanceof TaskListItemMarker marker) {
                task = marker.isChecked();
            } else if (first instanceof Paragraph para && para.getFirstChild() instanceof TaskListItemMarker marker) {
                task = marker.isChecked();
            }
            PendingMarker pending = new PendingMarker(numId, Math.min(level, 8), task);
            Ctx itemCtx = c.list(indent, list.isTight(), pending);
            boolean any = false;
            for (Node child = li.getFirstChild(); child != null; child = child.getNext()) {
                if (child instanceof TaskListItemMarker) {
                    continue;
                }
                renderBlock(child, itemCtx);
                any = true;
            }
            if (!any || !pending.used) {
                newParagraph(itemCtx, null); // empty list item
            }
        }
    }

    private static int depthOf(Node list) {
        int depth = 0;
        for (Node n = list.getParent(); n != null; n = n.getParent()) {
            if (n instanceof ListItem) {
                depth++;
            }
        }
        return depth;
    }

    private void renderAlert(Alert alert, Ctx c) {
        String type = alert.getType().toUpperCase(Locale.ROOT);
        String color = ALERT_COLORS.getOrDefault(type, "59636E");
        Ctx q = c.withoutMarker().quote(color);
        String title = AlertsExtension.STANDARD_TYPES.getOrDefault(type, capitalize(type));
        Node first = alert.getFirstChild();
        if (first != null && first.getClass().getSimpleName().equals("AlertTitle")) {
            title = Nodes.textContent(first).trim();
            first = first.getNext();
        }
        XWPFParagraph tp = newParagraph(q, null);
        tp.setSpacingAfter(60);
        XWPFRun run = tp.createRun();
        run.setText(title);
        run.setBold(true);
        run.setColor(color);
        for (Node child = first; child != null; child = child.getNext()) {
            renderBlock(child, q);
        }
    }

    private void renderCode(String literal, Ctx c) {
        XWPFParagraph p = newParagraph(c, "SourceCode");
        if (c.indent() > 0) {
            p.setIndentationLeft(c.indent() + 100);
        }
        String text = literal.endsWith("\n") ? literal.substring(0, literal.length() - 1) : literal;
        String[] lines = text.replace("\t", "    ").split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            XWPFRun run = p.createRun();
            run.setText(lines[i]);
            if (i < lines.length - 1) {
                run.addBreak();
            }
        }
    }

    private void renderRule(Ctx c) {
        XWPFParagraph p = newParagraph(c, null);
        CTPPr ppr = p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
        CTPBdr bdr = ppr.isSetPBdr() ? ppr.getPBdr() : ppr.addNewPBdr();
        CTBorder bottom = bdr.addNewBottom();
        bottom.setVal(STBorder.SINGLE);
        bottom.setSz(BigInteger.valueOf(12));
        bottom.setSpace(BigInteger.ONE);
        bottom.setColor("D1D9E0");
        p.setSpacingAfter(240);
    }

    private void renderDiagram(FencedCodeBlock block, Ctx c) {
        RenderedDiagram d = ctx.diagram(block, true);
        String language = Nodes.language(block.getInfo());
        if (d.failed() || !d.hasPng()) {
            XWPFParagraph p = newParagraph(c, null);
            XWPFRun run = p.createRun();
            run.setBold(true);
            run.setColor("82071E");
            run.setText(HtmlBuilder.label(language) + " diagram error: " + d.error());
            renderCode(block.getLiteral(), c.withoutMarker());
            return;
        }
        XWPFParagraph p = newParagraph(c, "Figure");
        try {
            int[] px = ImageSupport.readSize(d.png());
            // logical size comes from the renderer; the bitmap is usually denser
            double density = d.width() > 0 ? px[0] / d.width() : d.pngScale();
            ImageSupport.Raster raster = new ImageSupport.Raster(d.png(), "image/png", px[0], px[1], density);
            addPicture(p.createRun(), raster, HtmlBuilder.label(language) + " diagram", null, null, c.indent());
        } catch (Exception e) {
            p.createRun().setText("[" + HtmlBuilder.label(language) + " diagram: " + e.getMessage() + "]");
        }
    }

    private void renderHtmlBlock(HtmlBlock html, Ctx c) {
        String literal = html.getLiteral().trim();
        if (literal.isEmpty() || (literal.startsWith("<!--") && literal.endsWith("-->"))) {
            return;
        }
        org.jsoup.nodes.Document fragment = Jsoup.parseBodyFragment(literal);
        boolean centered = !fragment.select("[align=center], center").isEmpty();
        for (Element img : fragment.select("img[src]")) {
            XWPFParagraph p = newParagraph(c, null);
            if (centered) {
                p.setAlignment(ParagraphAlignment.CENTER);
            }
            appendImage(p, runSink(p), img.attr("src"), img.attr("alt"), img.attr("width"), img.attr("height"),
                    c.indent());
        }
        fragment.select("img, script, style").remove();
        for (Element br : fragment.select("br")) {
            br.after("\n");
        }
        String text = fragment.body().wholeText().replaceAll("[ \\t]+", " ").trim();
        if (!text.isEmpty()) {
            XWPFParagraph p = newParagraph(c, null);
            if (centered) {
                p.setAlignment(ParagraphAlignment.CENTER);
            }
            String[] lines = text.split("\\s*\n\\s*");
            for (int i = 0; i < lines.length; i++) {
                XWPFRun run = p.createRun();
                run.setText(lines[i]);
                if (i < lines.length - 1) {
                    run.addBreak();
                }
            }
        }
    }

    // ------------------------------------------------------------------ tables

    private void renderTable(TableBlock table, Ctx c) {
        List<TableRow> rows = new ArrayList<>();
        table.accept(new AbstractVisitor() {
            @Override
            public void visit(org.commonmark.node.CustomNode node) {
                if (node instanceof TableRow row) {
                    rows.add(row);
                } else {
                    visitChildren(node);
                }
            }

            @Override
            public void visit(org.commonmark.node.CustomBlock node) {
                visitChildren(node);
            }
        });
        if (rows.isEmpty()) {
            return;
        }
        int cols = rows.stream().mapToInt(r -> Nodes.children(r).size()).max().orElse(1);
        XWPFTable t = c.body().table(rows.size(), cols);
        if (t == null) { // nested tables are not supported in this container: fall back to text
            for (TableRow row : rows) {
                XWPFParagraph p = newParagraph(c, null);
                List<Node> cells = Nodes.children(row);
                for (int i = 0; i < cells.size(); i++) {
                    if (i > 0) {
                        p.createRun().setText(" | ");
                    }
                    renderInlines(cells.get(i), p, runSink(p), new Fmt());
                }
            }
            return;
        }
        t.setStyleID("MarkdownTable");
        int available = Math.max(2000, contentWidthTwips - c.indent());
        t.setWidth(String.valueOf(available));
        t.setWidthType(TableWidthType.DXA);
        if (c.indent() > 0) {
            CTTblPr tblPr = t.getCTTbl().getTblPr() != null ? t.getCTTbl().getTblPr() : t.getCTTbl().addNewTblPr();
            CTTblWidth ind = tblPr.isSetTblInd() ? tblPr.getTblInd() : tblPr.addNewTblInd();
            ind.setW(BigInteger.valueOf(c.indent()));
            ind.setType(STTblWidth.DXA);
        }
        int[] widths = columnWidths(rows, cols, available);
        for (int r = 0; r < rows.size(); r++) {
            XWPFTableRow xrow = t.getRow(r);
            List<Node> cells = Nodes.children(rows.get(r));
            boolean header = !cells.isEmpty() && cells.get(0) instanceof TableCell tc && tc.isHeader();
            if (header) {
                xrow.setRepeatHeader(true);
                xrow.setCantSplitRow(true);
            }
            for (int col = 0; col < cols; col++) {
                XWPFTableCell cell = xrow.getCell(col);
                if (cell == null) {
                    cell = xrow.addNewTableCell();
                }
                cell.setWidth(String.valueOf(widths[col]));
                cell.setWidthType(TableWidthType.DXA);
                if (header) {
                    cell.setColor("F3F5F7");
                }
                XWPFParagraph p = cell.getParagraphs().isEmpty() ? cell.addParagraph() : cell.getParagraphs().get(0);
                p.setStyle("TableText");
                if (col < cells.size() && cells.get(col) instanceof TableCell tc) {
                    if (tc.getAlignment() != null) {
                        switch (tc.getAlignment()) {
                            case CENTER -> p.setAlignment(ParagraphAlignment.CENTER);
                            case RIGHT -> p.setAlignment(ParagraphAlignment.RIGHT);
                            default -> p.setAlignment(ParagraphAlignment.LEFT);
                        }
                    }
                    Fmt fmt = new Fmt();
                    fmt.bold = tc.isHeader();
                    renderInlines(tc, p, runSink(p), fmt);
                }
            }
        }
        if (c.body().isDocument()) {
            XWPFParagraph spacer = c.body().paragraph();
            spacer.setSpacingAfter(0);
            spacer.setSpacingBefore(0);
            XWPFRun r = spacer.createRun();
            r.setFontSize(6);
        }
    }

    /** Distributes the width proportionally to (capped) content length. */
    private static int[] columnWidths(List<TableRow> rows, int cols, int available) {
        double[] weight = new double[cols];
        for (TableRow row : rows) {
            List<Node> cells = Nodes.children(row);
            for (int i = 0; i < cells.size() && i < cols; i++) {
                weight[i] = Math.max(weight[i], Math.min(60, Nodes.textContent(cells.get(i)).length()));
            }
        }
        double total = 0;
        for (int i = 0; i < cols; i++) {
            weight[i] = Math.max(4, weight[i]);
            total += weight[i];
        }
        int[] widths = new int[cols];
        for (int i = 0; i < cols; i++) {
            widths[i] = (int) Math.max(500, available * weight[i] / total);
        }
        return widths;
    }

    // ------------------------------------------------------------------ inlines

    private interface RunSink {
        XWPFRun newRun();
    }

    private static RunSink runSink(XWPFParagraph p) {
        return p::createRun;
    }

    private void renderInlines(Node parent, XWPFParagraph p, RunSink sink, Fmt fmt) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            renderInline(node, p, sink, fmt);
        }
    }

    private void renderInline(Node node, XWPFParagraph p, RunSink sink, Fmt fmt) {
        if (node instanceof Text text) {
            text(sink, fmt, text.getLiteral());
        } else if (node instanceof Code code) {
            Fmt f = fmt.copy();
            f.code = true;
            text(sink, f, code.getLiteral());
        } else if (node instanceof Emphasis) {
            Fmt f = fmt.copy();
            f.italic = true;
            renderInlines(node, p, sink, f);
        } else if (node instanceof StrongEmphasis) {
            Fmt f = fmt.copy();
            f.bold = true;
            renderInlines(node, p, sink, f);
        } else if (node instanceof Strikethrough) {
            Fmt f = fmt.copy();
            f.strike = true;
            renderInlines(node, p, sink, f);
        } else if (node instanceof Ins) {
            Fmt f = fmt.copy();
            f.underline = true;
            renderInlines(node, p, sink, f);
        } else if (node instanceof SoftLineBreak) {
            text(sink, fmt, " ");
        } else if (node instanceof HardLineBreak) {
            sink.newRun().addBreak();
        } else if (node instanceof Link link) {
            renderLink(link, p, fmt);
        } else if (node instanceof Image image) {
            String[] size = imageSize(image);
            appendImage(p, sink, image.getDestination(), Nodes.textContent(image), size[0], size[1], 0);
        } else if (node instanceof HtmlInline html) {
            renderHtmlInline(html.getLiteral(), p, sink, fmt);
        } else if (node instanceof FootnoteReference ref) {
            FootnoteDefinition def = footnoteDefinitions.get(ref.getLabel());
            if (def != null) {
                addFootnote(p, def, true);
            } else {
                text(sink, fmt, "[^" + ref.getLabel() + "]");
            }
        } else if (node instanceof InlineFootnote inline) {
            addFootnote(p, inline, false);
        } else if (node instanceof TaskListItemMarker marker) {
            XWPFRun run = sink.newRun();
            run.setText(marker.isChecked() ? "\u2611 " : "\u2610 ");
            run.setFontFamily(SYMBOL_FONT);
        } else if (node instanceof ImageAttributes) {
            // consumed by the image
        } else {
            renderInlines(node, p, sink, fmt);
        }
    }

    private void text(RunSink sink, Fmt fmt, String text) {
        if (text.isEmpty()) {
            return;
        }
        XWPFRun run = sink.newRun();
        apply(run, fmt);
        run.setText(text);
    }

    private static void apply(XWPFRun run, Fmt f) {
        if (f.code) {
            run.setStyle("InlineCode");
        }
        if (f.link) {
            if (f.code) {
                run.setColor(LINK_COLOR);
                run.setUnderline(UnderlinePatterns.SINGLE);
            } else {
                run.setStyle("Hyperlink");
            }
        }
        if (f.bold) {
            run.setBold(true);
        }
        if (f.italic) {
            run.setItalic(true);
        }
        if (f.strike) {
            run.setStrikeThrough(true);
        }
        if (f.underline) {
            run.setUnderline(UnderlinePatterns.SINGLE);
        }
        if (f.sup) {
            run.setVerticalAlignment(VerticalAlign.SUPERSCRIPT.toString().toLowerCase(Locale.ROOT));
        } else if (f.sub) {
            run.setVerticalAlignment(VerticalAlign.SUBSCRIPT.toString().toLowerCase(Locale.ROOT));
        }
        if (f.color != null) {
            run.setColor(f.color);
        }
    }

    private void renderLink(Link link, XWPFParagraph p, Fmt fmt) {
        String dest = link.getDestination() == null ? "" : link.getDestination().trim();
        if (dest.isEmpty()) {
            renderInlines(link, p, runSink(p), fmt);
            return;
        }
        CTHyperlink hyperlink = p.getCTP().addNewHyperlink();
        if (dest.startsWith("#")) {
            hyperlink.setAnchor(bookmarkName(dest.substring(1)));
        } else {
            hyperlink.setId(relationFor(p, dest));
        }
        if (link.getTitle() != null && !link.getTitle().isBlank()) {
            hyperlink.setTooltip(link.getTitle());
        }
        RunSink sink = () -> new XWPFHyperlinkRun(hyperlink, hyperlink.addNewR(), p);
        Fmt f = fmt.copy();
        f.link = true;
        renderInlines(link, p, sink, f);
    }

    private String relationFor(XWPFParagraph p, String url) {
        boolean inDocument = p.getPart() == doc;
        if (inDocument) {
            String existing = externalRelations.get(url);
            if (existing != null) {
                return existing;
            }
        }
        PackageRelationship rel = p.getPart().getPackagePart()
                .addExternalRelationship(url, XWPFRelation.HYPERLINK.getRelation());
        if (inDocument) {
            externalRelations.put(url, rel.getId());
        }
        return rel.getId();
    }

    private void renderHtmlInline(String literal, XWPFParagraph p, RunSink sink, Fmt fmt) {
        String tag = literal.trim().toLowerCase(Locale.ROOT);
        if (tag.startsWith("<!--")) {
            return;
        }
        boolean closing = tag.startsWith("</");
        String name = tag.replaceAll("^</?\\s*([a-z0-9]+).*$", "$1");
        switch (name) {
            case "br" -> sink.newRun().addBreak();
            case "b", "strong" -> fmt.bold = !closing;
            case "i", "em" -> fmt.italic = !closing;
            case "u", "ins" -> fmt.underline = !closing;
            case "s", "del", "strike" -> fmt.strike = !closing;
            case "sup" -> fmt.sup = !closing;
            case "sub" -> fmt.sub = !closing;
            case "code", "kbd", "tt", "samp" -> fmt.code = !closing;
            case "mark" -> fmt.color = closing ? null : "9A6700";
            case "img" -> {
                Element img = Jsoup.parseBodyFragment(literal).selectFirst("img");
                if (img != null) {
                    appendImage(p, sink, img.attr("src"), img.attr("alt"), img.attr("width"), img.attr("height"), 0);
                }
            }
            default -> {
                // other inline HTML is ignored, its text content is still rendered
            }
        }
    }

    // ------------------------------------------------------------------ footnotes

    private void addFootnote(XWPFParagraph p, Node content, boolean blockContent) {
        XWPFAbstractFootnoteEndnote note = doc.createFootnote();
        p.addFootnoteReference(note);
        // createParagraph() inserts the footnote reference mark into the first paragraph
        XWPFParagraph first = note.getParagraphs().isEmpty() ? note.createParagraph() : note.getParagraphs().get(0);
        first.setStyle("FootnoteText");
        first.createRun().setText(" ");
        Body body = new ReusingBody(first, () -> {
            XWPFParagraph next = note.createParagraph();
            next.setStyle("FootnoteText");
            return next;
        }, null);
        Ctx footCtx = new Ctx(body, 0, 0, null, false, null, true);
        if (blockContent) {
            boolean firstBlock = true;
            for (Node child = content.getFirstChild(); child != null; child = child.getNext()) {
                if (firstBlock && child instanceof Paragraph para) {
                    XWPFParagraph target = body.paragraph();
                    renderInlines(para, target, runSink(target), new Fmt());
                } else {
                    renderBlock(child, footCtx);
                }
                firstBlock = false;
            }
        } else {
            XWPFParagraph target = body.paragraph();
            renderInlines(content, target, runSink(target), new Fmt());
        }
    }

    // ------------------------------------------------------------------ images

    private static String[] imageSize(Image image) {
        for (Node child = image.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof ImageAttributes attrs) {
                return new String[]{attrs.getAttributes().get("width"), attrs.getAttributes().get("height")};
            }
        }
        if (image.getNext() instanceof ImageAttributes attrs) {
            return new String[]{attrs.getAttributes().get("width"), attrs.getAttributes().get("height")};
        }
        return new String[]{null, null};
    }

    private void appendImage(XWPFParagraph p, RunSink sink, String src, String alt, String width, String height,
                             int indent) {
        ResourceLoader.Resource resource = ctx.resources().load(src).orElse(null);
        if (resource == null) {
            XWPFRun run = sink.newRun();
            run.setItalic(true);
            run.setColor("82071E");
            run.setText("[image: " + (alt == null || alt.isBlank() ? src : alt) + "]");
            return;
        }
        try {
            ImageSupport.Raster raster = ImageSupport.toRaster(resource, ctx.options().getDiagramScale());
            addPicture(sink.newRun(), raster, alt, parseLength(width), parseLength(height), indent);
        } catch (Exception e) {
            LOG.warning("Cannot embed image " + src + ": " + e.getMessage());
            XWPFRun run = sink.newRun();
            run.setItalic(true);
            run.setText("[image: " + (alt == null || alt.isBlank() ? src : alt) + "]");
        }
    }

    /** Returns a length in pixels, a negative value for a percentage (-0.5 = 50%), or null. */
    private static Double parseLength(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        try {
            if (v.endsWith("%")) {
                return -Double.parseDouble(v.substring(0, v.length() - 1)) / 100.0;
            }
            return Double.parseDouble(v.replace("px", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void addPicture(XWPFRun run, ImageSupport.Raster raster, String alt, Double reqWidth, Double reqHeight,
                            int indent) throws Exception {
        double maxW = (contentWidthTwips - indent) / 15.0; // 1 px = 15 twips at 96 dpi
        double maxH = contentHeightTwips / 15.0 * 0.92;
        double w = raster.logicalWidth();
        double h = raster.logicalHeight();
        double ratio = h / Math.max(1, w);
        if (reqWidth != null) {
            w = reqWidth < 0 ? maxW * -reqWidth : reqWidth;
            h = reqHeight != null && reqHeight > 0 ? reqHeight : w * ratio;
        } else if (reqHeight != null && reqHeight > 0) {
            h = reqHeight;
            w = h / ratio;
        }
        if (w > maxW) {
            h = h * maxW / w;
            w = maxW;
        }
        if (h > maxH) {
            w = w * maxH / h;
            h = maxH;
        }
        int type = switch (raster.mimeType()) {
            case "image/jpeg" -> Document.PICTURE_TYPE_JPEG;
            case "image/gif" -> Document.PICTURE_TYPE_GIF;
            case "image/bmp" -> Document.PICTURE_TYPE_BMP;
            default -> Document.PICTURE_TYPE_PNG;
        };
        String ext = raster.mimeType().substring(raster.mimeType().indexOf('/') + 1);
        int n = ++imageCounter;
        run.addPicture(new ByteArrayInputStream(raster.data()), type, "image" + n + "." + ext,
                (int) Math.round(w * Units.EMU_PER_PIXEL), (int) Math.round(h * Units.EMU_PER_PIXEL));
        if (alt != null && !alt.isBlank()) {
            try {
                CTR ctr = run.getCTR();
                CTInline inline = ctr.getDrawingArray(ctr.sizeOfDrawingArray() - 1).getInlineArray(0);
                inline.getDocPr().setDescr(alt);
            } catch (RuntimeException ignored) {
                // alt text is optional
            }
        }
    }

    // ------------------------------------------------------------------ utils

    static String bookmarkName(String id) {
        String s = "_" + id.replaceAll("[^A-Za-z0-9_]", "_");
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    private static int mmToTwips(double mm) {
        return (int) Math.round(mm / 25.4 * 1440);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : s.charAt(0) + s.substring(1).toLowerCase(Locale.ROOT);
    }
}
