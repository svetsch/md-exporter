---
title: Markdown Exporter
subtitle: Export Markdown to Word, HTML and PDF
author: md-exporter
---

# Getting started

Type Markdown on the left, the preview on the right updates as you type.
Use the *Export* menu (`Ctrl+Shift+D` / `H` / `P`) to export the document to Word, HTML or PDF.
Open or drop `.md` files onto the window: each one gets its own tab, next to this showcase
(reopen it any time with *Help → Feature Showcase*). Relative image paths are resolved against each file's folder.

> [!TIP]
> *Export → Export Multiple Formats…* writes all formats in one go, and *Export Options…* sets the
> page size, margins, table of contents, Mermaid theme and the appearance: style (GitHub, Classic,
> Modern, Academic, Minimal, Dark), accent colour, fonts and text size. The preview shows the chosen style.

## Text formatting

Plain, **bold**, *italic*, ***both***, ~~strikethrough~~, ++inserted++, `inline code`,
H<sub>2</sub>O, E = mc<sup>2</sup>, <kbd>Ctrl</kbd>+<kbd>S</kbd> and a [link](https://commonmark.org).
URLs such as https://github.com are linked automatically. Footnotes are supported too.[^1]

[^1]: Footnotes become real footnotes in Word, and are collected at the end in HTML and PDF.

## Lists

1. First item
2. Second item
   - nested bullet
   - another one
     1. deeper numbered
3. Third item

- [x] Task done
- [ ] Task to do

## Table

| Feature        | Word | HTML | PDF |
|:---------------|:----:|:----:|:---:|
| Tables         |  ✓   |  ✓   |  ✓  |
| Images         |  ✓   |  ✓   |  ✓  |
| Mermaid        |  ✓   |  ✓   |  ✓  |
| PlantUML       |  ✓   |  ✓   |  ✓  |
| Footnotes      |  ✓   |  ✓   |  ✓  |

## Mermaid diagrams

```mermaid
flowchart LR
    A[Markdown] --> B{Exporter}
    B -->|POI| C[Word .docx]
    B -->|HTML + CSS| D[HTML page]
    B -->|openhtmltopdf| E[PDF]
```

```mermaid
sequenceDiagram
    participant U as User
    participant A as App
    participant R as Renderer
    U->>A: Export to PDF
    A->>R: Render diagrams
    R-->>A: PNG / SVG
    A-->>U: document.pdf
```

## PlantUML diagram

```plantuml
@startuml
class Document {
  +title: String
  +export(format)
}
class Exporter <<interface>>
Document --> Exporter
Exporter <|.. WordExporter
Exporter <|.. HtmlExporter
Exporter <|.. PdfExporter
@enduml
```

## Code

```java
public record Greeting(String name) {
    public String text() {
        return "Hello, " + name + "!";
    }
}
```

## Alerts and quotes

> [!NOTE]
> Useful information that users should know.

> [!WARNING]
> Critical content demanding immediate user attention.

> Markdown is a lightweight markup language for creating formatted text.
> — *Wikipedia*

---

*Happy exporting!*
