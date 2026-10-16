package io.mdexporter.ui;

import io.mdexporter.ai.AiPrompts;
import io.mdexporter.editor.MarkdownEditing;
import io.mdexporter.editor.MarkdownEditing.ListKind;
import io.mdexporter.editor.MarkdownTable;
import io.mdexporter.editor.TextEdit;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCombination;
import javafx.scene.text.Text;
import javafx.stage.Window;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Markdown editing commands (formatting, insertion, tables) and the menus, tool bar and context menu exposing them.
 * Menu items are created on demand because a JavaFX menu item can only belong to one menu.
 */
final class EditorActions {

    /** What the actions need from the main window. */
    interface Context {
        DocumentTab current();

        Window window();

        void showStatus(String message);

        boolean aiEnabled();

        /** Opens the AI dialog; with a preset the request is sent right away. */
        void askAi(AiPrompts.Preset preset);

        /** Builds the "Agent" sub menu (radio items + manage). */
        Menu agentMenu();
    }

    /** A command: label, accelerator (may be null), action. {@code null} commands are separators. */
    private record Cmd(String text, String accelerator, String tooltip, Runnable action) {
    }

    private static final String[] CODE_LANGUAGES = {"java", "python", "javascript", "typescript", "json", "yaml",
            "xml", "html", "css", "sql", "bash", "powershell", "csharp", "go", "kotlin", "markdown", "text"};

    private static final String[][] DIAGRAMS = {
            {"Mermaid Flowchart", "mermaid", """
                    flowchart LR
                        A[Start] --> B{Decision}
                        B -->|Yes| C[Do it]
                        B -->|No| D[Skip]"""},
            {"Mermaid Sequence Diagram", "mermaid", """
                    sequenceDiagram
                        participant A as Alice
                        participant B as Bob
                        A->>B: Hello Bob
                        B-->>A: Hi Alice"""},
            {"Mermaid Class Diagram", "mermaid", """
                    classDiagram
                        class Animal {
                            +String name
                            +eat()
                        }
                        Animal <|-- Dog"""},
            {"Mermaid State Diagram", "mermaid", """
                    stateDiagram-v2
                        [*] --> Idle
                        Idle --> Running : start
                        Running --> Idle : stop"""},
            {"Mermaid Gantt Chart", "mermaid", """
                    gantt
                        title Plan
                        dateFormat YYYY-MM-DD
                        section Phase 1
                        Task A :a1, %s, 7d
                        Task B :after a1, 5d"""},
            {"Mermaid Pie Chart", "mermaid", """
                    pie title Share
                        "A" : 60
                        "B" : 40"""},
            {"PlantUML Sequence Diagram", "plantuml", """
                    @startuml
                    Alice -> Bob: Hello
                    Bob --> Alice: Hi!
                    @enduml"""},
            {"PlantUML Component Diagram", "plantuml", """
                    @startuml
                    [Client] --> [Server] : HTTP
                    [Server] --> [Database] : SQL
                    @enduml"""},
            {"Graphviz Graph", "dot", """
                    digraph G {
                        A -> B
                        B -> C
                    }"""}};

    private static final String[] ALERTS = {"NOTE", "TIP", "IMPORTANT", "WARNING", "CAUTION"};

    private final Context ctx;

    EditorActions(Context ctx) {
        this.ctx = ctx;
    }

    // ------------------------------------------------------------------ helpers

    private interface EditOp {
        TextEdit apply(String text, int start, int end);
    }

    /** Runs a text operation on the selection of the current document. */
    private void edit(EditOp op) {
        DocumentTab doc = ctx.current();
        if (doc == null) {
            return;
        }
        TextArea editor = doc.editor();
        TextEdit edit = op.apply(editor.getText(), editor.getSelection().getStart(), editor.getSelection().getEnd());
        if (edit != null) {
            doc.apply(edit);
        }
    }

    private void insert(String template, boolean block) {
        edit((t, s, e) -> MarkdownEditing.insert(t, s, e, template, block));
    }

    // ------------------------------------------------------------------ commands

    void inline(String open, String close) {
        edit((t, s, e) -> MarkdownEditing.toggleInline(t, s, e, open, close));
    }

    void heading(int level) {
        edit((t, s, e) -> MarkdownEditing.setHeading(t, s, e, level));
    }

    void list(ListKind kind) {
        edit((t, s, e) -> MarkdownEditing.toggleList(t, s, e, kind));
    }

    void codeBlock(String language) {
        edit((t, s, e) -> MarkdownEditing.codeBlock(t, s, e, language));
    }

    void diagram(String language, String body) {
        insert("```" + language + "\n" + MarkdownEditing.MARK_START + body.formatted(LocalDate.now())
                + MarkdownEditing.MARK_END + "\n```", true);
    }

    void insertLink() {
        DocumentTab doc = ctx.current();
        if (doc != null) {
            InsertDialogs.link(ctx.window(), doc.editor().getSelectedText().strip())
                    .ifPresent(md -> insert(md + MarkdownEditing.MARK_START, false));
        }
    }

    void insertImage() {
        DocumentTab doc = ctx.current();
        if (doc != null) {
            InsertDialogs.image(ctx.window(), doc.file() != null ? doc.file().getParent() : null,
                            doc.editor().getSelectedText().strip())
                    .ifPresent(md -> insert(md + MarkdownEditing.MARK_START, false));
        }
    }

    void insertTable() {
        if (ctx.current() == null) {
            return;
        }
        InsertDialogs.table(ctx.window()).ifPresent(md -> {
            insert(md, true);
            // select the first header cell
            DocumentTab doc = ctx.current();
            MarkdownTable.Located t = MarkdownTable.find(doc.editor().getText(), doc.editor().getCaretPosition());
            if (t != null) {
                doc.selectCell(t, 0, 0);
            }
        });
    }

    void formatTable() {
        if (!tableOp(t -> { })) {
            ctx.showStatus("Place the cursor in a table to format it, or use Format All Tables.");
        }
    }

    void formatAllTables() {
        DocumentTab doc = ctx.current();
        if (doc == null) {
            return;
        }
        String text = doc.editor().getText();
        int count = MarkdownTable.findAll(text).size();
        int caret = doc.editor().getCaretPosition();
        String formatted = MarkdownTable.formatAll(text);
        doc.replaceAll(formatted);
        doc.editor().positionCaret(Math.min(caret, formatted.length()));
        ctx.showStatus(count == 0 ? "No table found" : "Formatted " + count + (count == 1 ? " table" : " tables"));
    }

    /**
     * Modifies the table at the caret, re-formats it and keeps the caret in the (moved) cell.
     *
     * @return false when the caret is not in a table
     */
    private boolean tableOp(Consumer<MarkdownTable.Located> op) {
        return tableOp(op, 0, 0);
    }

    private boolean tableOp(Consumer<MarkdownTable.Located> op, int rowShift, int colShift) {
        DocumentTab doc = ctx.current();
        if (doc == null) {
            return false;
        }
        TextArea editor = doc.editor();
        MarkdownTable.Located t = MarkdownTable.find(editor.getText(), editor.getCaretPosition());
        if (t == null) {
            return false;
        }
        op.accept(t);
        int row = t.caretRow() + rowShift;
        int col = t.caretCol() + colShift;
        MarkdownTable.Formatted f = t.table().format();
        row = Math.max(0, Math.min(row, t.table().rows().size() - 1));
        col = Math.max(0, Math.min(col, t.table().columnCount() - 1));
        int cell = t.start() + f.cellStart(row, col);
        int caret = cell + Math.min(t.caretSkip(), t.table().rows().get(row).get(col).length());
        doc.apply(new TextEdit(t.start(), t.end(), f.text(), caret, caret));
        return true;
    }

    private void tableCommand(Consumer<MarkdownTable.Located> op, int rowShift, int colShift) {
        if (!tableOp(op, rowShift, colShift)) {
            ctx.showStatus("The cursor is not in a table");
        }
    }

    boolean caretInTable() {
        DocumentTab doc = ctx.current();
        return doc != null && MarkdownTable.find(doc.editor().getText(), doc.editor().getCaretPosition()) != null;
    }

    void goToLine() {
        DocumentTab doc = ctx.current();
        if (doc == null) {
            return;
        }
        TextArea editor = doc.editor();
        String text = editor.getText();
        int lines = MarkdownEditing.lineIndex(text, text.length()) + 1;
        int current = MarkdownEditing.lineIndex(text, editor.getCaretPosition()) + 1;
        InsertDialogs.goToLine(ctx.window(), current, lines).ifPresent(line -> {
            int pos = MarkdownEditing.lineOffset(text, line - 1);
            editor.selectRange(pos, pos);
            editor.requestFocus();
        });
    }

    // ------------------------------------------------------------------ command lists

    private List<Cmd> characterCommands() {
        List<Cmd> list = new ArrayList<>();
        list.add(new Cmd("_Bold", "Shortcut+B", "**bold**", () -> inline("**", "**")));
        list.add(new Cmd("_Italic", "Shortcut+I", "*italic*", () -> inline("*", "*")));
        list.add(new Cmd("_Strikethrough", "Shortcut+Shift+X", "~~strikethrough~~", () -> inline("~~", "~~")));
        list.add(new Cmd("_Underline (Inserted)", "Shortcut+U", "++inserted++", () -> inline("++", "++")));
        list.add(new Cmd("Inline _Code", "Shortcut+Shift+C", "`code`", () -> inline("`", "`")));
        list.add(new Cmd("Su_bscript", null, "<sub>", () -> inline("<sub>", "</sub>")));
        list.add(new Cmd("Su_perscript", null, "<sup>", () -> inline("<sup>", "</sup>")));
        list.add(new Cmd("_Keyboard Key", null, "<kbd>", () -> inline("<kbd>", "</kbd>")));
        list.add(null);
        list.add(new Cmd("Clear _Formatting", "Shortcut+Shift+Space", null,
                () -> edit(MarkdownEditing::clearFormatting)));
        return list;
    }

    private List<Cmd> headingCommands() {
        List<Cmd> list = new ArrayList<>();
        list.add(new Cmd("_Normal Text", "Shortcut+DIGIT0", null, () -> heading(0)));
        for (int level = 1; level <= 6; level++) {
            int l = level;
            list.add(new Cmd("Heading _" + level, "Shortcut+DIGIT" + level, null, () -> heading(l)));
        }
        return list;
    }

    private List<Cmd> paragraphCommands() {
        List<Cmd> list = new ArrayList<>();
        list.add(new Cmd("_Bullet List", "Shortcut+Shift+DIGIT8", null, () -> list(ListKind.BULLET)));
        list.add(new Cmd("_Numbered List", "Shortcut+Shift+DIGIT7", null, () -> list(ListKind.NUMBERED)));
        list.add(new Cmd("_Task List", "Shortcut+Shift+DIGIT9", null, () -> list(ListKind.TASK)));
        list.add(new Cmd("Toggle Task _Done", "Shortcut+ENTER", null, () -> edit(MarkdownEditing::toggleTaskDone)));
        list.add(new Cmd("_Quote", "Shortcut+Shift+Q", null, () -> list(ListKind.QUOTE)));
        list.add(null);
        list.add(new Cmd("_Indent", "Shortcut+CLOSE_BRACKET", null, () -> edit(MarkdownEditing::indent)));
        list.add(new Cmd("_Outdent", "Shortcut+OPEN_BRACKET", null, () -> edit(MarkdownEditing::outdent)));
        return list;
    }

    private List<Cmd> tableCommands() {
        List<Cmd> list = new ArrayList<>();
        list.add(new Cmd("_Insert Table...", "Shortcut+T", null, this::insertTable));
        list.add(new Cmd("_Format Table (Align Columns)", "Shortcut+Shift+F", null, this::formatTable));
        list.add(new Cmd("Format _All Tables", "Shortcut+Alt+Shift+F", null, this::formatAllTables));
        list.add(null);
        list.add(new Cmd("Insert Row _Above", null, null,
                () -> tableCommand(t -> t.table().insertRow(Math.max(1, t.caretRow())), 0, 0)));
        list.add(new Cmd("Insert Row _Below", "Shortcut+Shift+ENTER", null,
                () -> tableCommand(t -> t.table().insertRow(t.caretRow() + 1), 1, 0)));
        list.add(new Cmd("Insert Column _Left", null, null,
                () -> tableCommand(t -> t.table().insertColumn(t.caretCol()), 0, 0)));
        list.add(new Cmd("Insert Column _Right", null, null,
                () -> tableCommand(t -> t.table().insertColumn(t.caretCol() + 1), 0, 1)));
        list.add(null);
        list.add(new Cmd("Delete _Row", null, null, () -> tableCommand(t -> t.table().deleteRow(t.caretRow()), 0, 0)));
        list.add(new Cmd("Delete _Column", null, null,
                () -> tableCommand(t -> t.table().deleteColumn(t.caretCol()), 0, 0)));
        list.add(null);
        list.add(new Cmd("Move Row _Up", null, null,
                () -> tableCommand(t -> t.table().moveRow(t.caretRow(), -1), -1, 0)));
        list.add(new Cmd("Move Row Do_wn", null, null,
                () -> tableCommand(t -> t.table().moveRow(t.caretRow(), 1), 1, 0)));
        list.add(new Cmd("Move Column Le_ft", null, null,
                () -> tableCommand(t -> t.table().moveColumn(t.caretCol(), -1), 0, -1)));
        list.add(new Cmd("Move Column Ri_ght", null, null,
                () -> tableCommand(t -> t.table().moveColumn(t.caretCol(), 1), 0, 1)));
        list.add(null);
        for (MarkdownTable.Align align : MarkdownTable.Align.values()) {
            String name = align == MarkdownTable.Align.NONE ? "Default"
                    : align.name().charAt(0) + align.name().substring(1).toLowerCase();
            list.add(new Cmd("Align Column: " + name, null, null,
                    () -> tableCommand(t -> t.table().align(t.caretCol(), align), 0, 0)));
        }
        return list;
    }

    private List<Cmd> insertCommands() {
        List<Cmd> list = new ArrayList<>();
        list.add(new Cmd("_Link...", "Shortcut+K", null, this::insertLink));
        list.add(new Cmd("_Image...", "Shortcut+Shift+I", null, this::insertImage));
        list.add(new Cmd("_Table...", null, null, this::insertTable));
        list.add(new Cmd("_Code Block", "Shortcut+Shift+K", null, () -> codeBlock("")));
        return list;
    }

    private List<Cmd> insertMoreCommands() {
        List<Cmd> list = new ArrayList<>();
        list.add(new Cmd("_Horizontal Rule", null, null, () -> insert("---" + MarkdownEditing.MARK_START, true)));
        list.add(new Cmd("_Footnote", "Shortcut+Alt+F", null, () -> edit(MarkdownEditing::footnote)));
        list.add(new Cmd("Line _Break", null, null, () -> insert("<br>\n" + MarkdownEditing.MARK_START,
                false)));
        list.add(new Cmd("Front _Matter (Title, Author, Date)", null, null,
                () -> edit((t, s, e) -> MarkdownEditing.frontMatter(t, LocalDate.now().toString()))));
        list.add(new Cmd("Current _Date", null, null, () -> insert(LocalDate.now().toString(), false)));
        return list;
    }

    // ------------------------------------------------------------------ menu construction

    private static MenuItem item(Cmd cmd, boolean accelerators) {
        MenuItem item = new MenuItem(cmd.text());
        if (accelerators && cmd.accelerator() != null) {
            item.setAccelerator(KeyCombination.keyCombination(cmd.accelerator()));
        }
        item.setOnAction(e -> cmd.action().run());
        return item;
    }

    private static List<MenuItem> items(List<Cmd> cmds, boolean accelerators) {
        List<MenuItem> items = new ArrayList<>();
        for (Cmd cmd : cmds) {
            items.add(cmd == null ? new SeparatorMenuItem() : item(cmd, accelerators));
        }
        return items;
    }

    private Menu headingMenu(boolean accelerators) {
        Menu menu = new Menu("_Heading");
        menu.getItems().setAll(items(headingCommands(), accelerators));
        return menu;
    }

    private Menu codeBlockMenu(boolean accelerators) {
        Menu menu = new Menu("Code Block (_Language)");
        for (String lang : CODE_LANGUAGES) {
            MenuItem item = new MenuItem(lang);
            item.setMnemonicParsing(false);
            item.setOnAction(e -> codeBlock(lang));
            menu.getItems().add(item);
        }
        return menu;
    }

    private Menu diagramMenu() {
        Menu menu = new Menu("_Diagram");
        for (String[] d : DIAGRAMS) {
            MenuItem item = new MenuItem(d[0]);
            item.setOnAction(e -> diagram(d[1], d[2]));
            menu.getItems().add(item);
        }
        return menu;
    }

    private Menu alertMenu() {
        Menu menu = new Menu("_Alert");
        for (String type : ALERTS) {
            MenuItem item = new MenuItem(type.charAt(0) + type.substring(1).toLowerCase());
            item.setOnAction(e -> insert("> [!" + type + "]\n> " + MarkdownEditing.MARK_START
                    + MarkdownEditing.SELECTION.replace("\n", "\n> "), true));
            menu.getItems().add(item);
        }
        return menu;
    }

    /** Format menu: character formatting, headings, lists. */
    Menu formatMenu(boolean accelerators) {
        Menu menu = new Menu("F_ormat");
        menu.getItems().addAll(items(characterCommands(), accelerators));
        menu.getItems().addAll(new SeparatorMenuItem(), headingMenu(accelerators), new SeparatorMenuItem());
        menu.getItems().addAll(items(paragraphCommands(), accelerators));
        return menu;
    }

    Menu insertMenu(boolean accelerators) {
        Menu menu = new Menu("_Insert");
        menu.getItems().addAll(items(insertCommands(), accelerators));
        menu.getItems().addAll(codeBlockMenu(accelerators), diagramMenu(), alertMenu(), new SeparatorMenuItem());
        menu.getItems().addAll(items(insertMoreCommands(), accelerators));
        return menu;
    }

    Menu tableMenu(boolean accelerators) {
        Menu menu = new Menu("_Table");
        List<MenuItem> items = items(tableCommands(), accelerators);
        menu.getItems().addAll(items);
        // row / column commands need the caret in a table
        menu.setOnShowing(e -> {
            boolean inTable = caretInTable();
            for (int i = 2; i < items.size(); i++) {
                items.get(i).setDisable(!inTable && !(items.get(i) instanceof SeparatorMenuItem));
            }
            items.get(1).setDisable(!inTable);
        });
        return menu;
    }

    Menu editMenu() {
        Menu menu = new Menu("_Edit");
        menu.getItems().addAll(
                editorItem("_Undo", "Shortcut+Z", TextArea::undo),
                editorItem("_Redo", "Shortcut+Y", TextArea::redo),
                new SeparatorMenuItem(),
                editorItem("Cu_t", "Shortcut+X", TextArea::cut),
                editorItem("_Copy", "Shortcut+C", TextArea::copy),
                editorItem("_Paste", "Shortcut+V", TextArea::paste),
                editorItem("Select _All", "Shortcut+A", TextArea::selectAll),
                new SeparatorMenuItem(),
                item(new Cmd("_Find...", "Shortcut+F", null, () -> find(false)), true),
                item(new Cmd("R_eplace...", "Shortcut+H", null, () -> find(true)), true),
                item(new Cmd("Find _Next", "F3", null, () -> findNext(true)), true),
                item(new Cmd("Find Pre_vious", "Shift+F3", null, () -> findNext(false)), true),
                new SeparatorMenuItem(),
                item(new Cmd("_Go to Line...", "Shortcut+G", null, this::goToLine), true));
        return menu;
    }

    private MenuItem editorItem(String text, String accelerator, Consumer<TextArea> action) {
        // A focused text area consumes these keys itself, so the accelerator only fires when it is not focused.
        MenuItem item = new MenuItem(text);
        item.setAccelerator(KeyCombination.keyCombination(accelerator));
        item.setOnAction(e -> {
            DocumentTab doc = ctx.current();
            if (doc != null) {
                action.accept(doc.editor());
                doc.editor().requestFocus();
            }
        });
        return item;
    }

    void find(boolean replace) {
        DocumentTab doc = ctx.current();
        if (doc != null) {
            doc.findBar().show(replace);
        }
    }

    void findNext(boolean forward) {
        DocumentTab doc = ctx.current();
        if (doc != null) {
            doc.findBar().find(forward);
        }
    }

    // ------------------------------------------------------------------ tool bar

    /** Second tool bar row with the most used formatting commands. */
    ToolBar formatBar() {
        MenuButton headings = new MenuButton("¶");
        headings.getItems().setAll(items(headingCommands(), false));
        headings.setTooltip(new Tooltip("Heading level / normal text (Ctrl+1 ... Ctrl+6, Ctrl+0)"));

        MenuButton code = new MenuButton("Code");
        code.setTooltip(new Tooltip("Insert a code block (Ctrl+Shift+K)"));
        code.getItems().add(item(new Cmd("Plain Code Block", null, null, () -> codeBlock("")), false));
        code.getItems().add(new SeparatorMenuItem());
        for (String lang : CODE_LANGUAGES) {
            MenuItem item = new MenuItem(lang);
            item.setMnemonicParsing(false);
            item.setOnAction(e -> codeBlock(lang));
            code.getItems().add(item);
        }

        MenuButton table = new MenuButton("Table");
        table.setTooltip(new Tooltip("Insert, format and edit tables"));
        Menu tableMenu = tableMenu(false);
        table.getItems().setAll(tableMenu.getItems());
        table.setOnShowing(e -> tableMenu.getOnShowing().handle(null));

        MenuButton more = new MenuButton("More");
        more.setTooltip(new Tooltip("Diagrams, alerts, horizontal rule, footnotes, front matter..."));
        more.getItems().addAll(diagramMenu(), alertMenu());
        more.getItems().addAll(items(insertMoreCommands(), false));
        more.getItems().addAll(new SeparatorMenuItem(),
                item(new Cmd("Subscript", null, null, () -> inline("<sub>", "</sub>")), false),
                item(new Cmd("Superscript", null, null, () -> inline("<sup>", "</sup>")), false),
                item(new Cmd("Keyboard Key", null, null, () -> inline("<kbd>", "</kbd>")), false),
                item(new Cmd("Clear Formatting", null, null, () -> edit(MarkdownEditing::clearFormatting)), false));

        return new ToolBar(
                headings,
                new Separator(),
                styled(fmt("B", "Bold (Ctrl+B)", () -> inline("**", "**")), "-fx-font-weight: bold;"),
                styled(fmt("I", "Italic (Ctrl+I)", () -> inline("*", "*")), "-fx-font-style: italic;"),
                strike(fmt("S", "Strikethrough (Ctrl+Shift+X)", () -> inline("~~", "~~"))),
                styled(fmt("U", "Underline / inserted text: ++text++ (Ctrl+U)", () -> inline("++", "++")),
                        "-fx-underline: true;"),
                styled(fmt("<>", "Inline code (Ctrl+Shift+C)", () -> inline("`", "`")), "-fx-font-family: Consolas;"),
                new Separator(),
                fmt("Link", "Insert a link (Ctrl+K)", this::insertLink),
                fmt("Image", "Insert an image (Ctrl+Shift+I)", this::insertImage),
                new Separator(),
                fmt("•", "Bullet list (Ctrl+Shift+8)", () -> list(ListKind.BULLET)),
                fmt("1.", "Numbered list (Ctrl+Shift+7)", () -> list(ListKind.NUMBERED)),
                fmt("☑", "Task list (Ctrl+Shift+9); Ctrl+Enter ticks a task",
                        () -> list(ListKind.TASK)),
                fmt("❝", "Block quote (Ctrl+Shift+Q)", () -> list(ListKind.QUOTE)),
                fmt("⇥", "Indent (Ctrl+])", () -> edit(MarkdownEditing::indent)),
                fmt("⇤", "Outdent (Ctrl+[)", () -> edit(MarkdownEditing::outdent)),
                new Separator(),
                table,
                fmt("≣", "Format the table at the cursor: align its columns (Ctrl+Shift+F)",
                        this::formatTable),
                code,
                more);
    }

    private static Button fmt(String text, String tooltip, Runnable action) {
        Button b = new Button(text);
        b.setTooltip(new Tooltip(tooltip));
        b.setOnAction(e -> action.run());
        b.setFocusTraversable(false);
        b.getStyleClass().add("format-button");
        return b;
    }

    private static Button styled(Button b, String style) {
        b.setStyle(style);
        return b;
    }

    private static Button strike(Button b) {
        Text text = new Text(b.getText());
        text.setStrikethrough(true);
        text.getStyleClass().add("format-glyph");
        b.setText(null);
        b.setGraphic(text);
        return b;
    }

    // ------------------------------------------------------------------ context menu

    /** Context menu of an editor; the entries adapt to the selection, tables and the AI setting. */
    ContextMenu contextMenu(DocumentTab doc) {
        TextArea editor = doc.editor();
        MenuItem undo = new MenuItem("Undo");
        undo.setOnAction(e -> editor.undo());
        MenuItem redo = new MenuItem("Redo");
        redo.setOnAction(e -> editor.redo());
        MenuItem cut = new MenuItem("Cut");
        cut.setOnAction(e -> editor.cut());
        MenuItem copy = new MenuItem("Copy");
        copy.setOnAction(e -> editor.copy());
        MenuItem paste = new MenuItem("Paste");
        paste.setOnAction(e -> editor.paste());
        MenuItem delete = new MenuItem("Delete");
        delete.setOnAction(e -> editor.replaceSelection(""));
        MenuItem selectAll = new MenuItem("Select All");
        selectAll.setOnAction(e -> editor.selectAll());

        // quick formatting of the selection
        List<MenuItem> selectionItems = List.of(
                new SeparatorMenuItem(),
                item(new Cmd("Bold", null, null, () -> inline("**", "**")), false),
                item(new Cmd("Italic", null, null, () -> inline("*", "*")), false),
                item(new Cmd("Strikethrough", null, null, () -> inline("~~", "~~")), false),
                item(new Cmd("Inline Code", null, null, () -> inline("`", "`")), false),
                item(new Cmd("Link...", null, null, this::insertLink), false),
                item(new Cmd("Clear Formatting", null, null, () -> edit(MarkdownEditing::clearFormatting)), false));

        Menu format = new Menu("Format");
        format.getItems().addAll(items(characterCommands(), false));
        Menu paragraph = new Menu("Paragraph");
        paragraph.getItems().add(headingMenu(false));
        paragraph.getItems().add(new SeparatorMenuItem());
        paragraph.getItems().addAll(items(paragraphCommands(), false));
        Menu insert = insertMenu(false);
        insert.setText("Insert");
        Menu table = tableMenu(false);
        table.setText("Table");
        MenuItem formatTable = item(new Cmd("Format Table (Align Columns)", null, null, this::formatTable), false);

        Menu ai = new Menu("Ask AI");
        MenuItem askAi = new MenuItem("Ask AI...");
        askAi.setOnAction(e -> ctx.askAi(null));
        ai.getItems().addAll(askAi, new SeparatorMenuItem());
        for (AiPrompts.Preset preset : AiPrompts.PRESETS) {
            MenuItem item = new MenuItem(preset.label());
            item.setOnAction(e -> ctx.askAi(preset));
            ai.getItems().add(item);
        }
        SeparatorMenuItem aiSeparator = new SeparatorMenuItem();

        ContextMenu menu = new ContextMenu(undo, redo, new SeparatorMenuItem(), cut, copy, paste, delete,
                new SeparatorMenuItem(), selectAll);
        menu.getItems().addAll(selectionItems);
        menu.getItems().addAll(new SeparatorMenuItem(), format, paragraph, insert, new SeparatorMenuItem(),
                formatTable, table, aiSeparator, ai);
        menu.setOnShowing(e -> {
            boolean selection = editor.getSelection().getLength() > 0;
            boolean readOnly = !editor.isEditable();
            undo.setDisable(!editor.isUndoable());
            redo.setDisable(!editor.isRedoable());
            cut.setDisable(!selection || readOnly);
            copy.setDisable(!selection);
            delete.setDisable(!selection || readOnly);
            paste.setDisable(!javafx.scene.input.Clipboard.getSystemClipboard().hasString());
            selectionItems.forEach(i -> i.setVisible(selection));
            boolean inTable = caretInTable();
            table.setVisible(inTable);
            formatTable.setVisible(inTable);
            if (inTable) {
                table.getOnShowing().handle(null);
            }
            ai.setVisible(ctx.aiEnabled());
            aiSeparator.setVisible(ctx.aiEnabled());
            ai.setText(selection ? "Ask AI about the Selection" : "Ask AI about the Document");
        });
        return menu;
    }

    /** AI menu of the menu bar. */
    Menu aiMenu() {
        Menu menu = new Menu("_AI");
        MenuItem ask = new MenuItem("_Ask AI...");
        ask.setAccelerator(KeyCombination.keyCombination("Shortcut+Shift+A"));
        ask.setOnAction(e -> ctx.askAi(null));
        menu.getItems().addAll(ask, new SeparatorMenuItem());
        for (AiPrompts.Preset preset : AiPrompts.PRESETS) {
            MenuItem item = new MenuItem(preset.label());
            item.setOnAction(e -> ctx.askAi(preset));
            menu.getItems().add(item);
        }
        Menu agents = ctx.agentMenu();
        menu.getItems().addAll(new SeparatorMenuItem(), agents);
        menu.setOnShowing(e -> {
            Menu fresh = ctx.agentMenu();
            agents.getItems().setAll(fresh.getItems());
        });
        return menu;
    }

    /** AI button of the format bar. */
    MenuButton aiButton() {
        MenuButton button = new MenuButton("✨ AI");
        button.setTooltip(new Tooltip("Ask the AI to change the selection or the document (Ctrl+Shift+A)"));
        button.getStyleClass().add("ai-button");
        MenuItem ask = new MenuItem("Ask AI...");
        ask.setOnAction(e -> ctx.askAi(null));
        button.getItems().addAll(ask, new SeparatorMenuItem());
        for (AiPrompts.Preset preset : AiPrompts.PRESETS) {
            MenuItem item = new MenuItem(preset.label());
            item.setOnAction(e -> ctx.askAi(preset));
            button.getItems().add(item);
        }
        Menu agents = ctx.agentMenu();
        button.getItems().addAll(new SeparatorMenuItem(), agents);
        button.setOnShowing(e -> agents.getItems().setAll(ctx.agentMenu().getItems()));
        return button;
    }
}
