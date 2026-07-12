package model

/** Represents a single piece of text at a specific location inside a pptx file.
  *
  * @param slideIndex     zero-based slide number
  * @param shapeIndex     zero-based shape index on the slide
  * @param tableRowIndex  Some(row) if the shape is a cell in a table
  * @param paragraphIndex zero-based paragraph within the text shape
  * @param runIndex       zero-based text run within the paragraph
  * @param originalText   the extracted Ukrainian text
  * @param translatedText the translated Russian text (populated after API call)
  */
case class TextElement(
    slideIndex: Int,
    shapeIndex: Int,
    tableRowIndex: Option[Int],
    paragraphIndex: Int,
    runIndex: Int,
    originalText: String,
    translatedText: String = ""
)
