package service

import model.{TextElement, Batch}
import java.io.File

/** Shared document processing utilities.
  *
  * Contains the batching logic used by both PptxService and DocxService.
  */
object DocumentService:

  /** Separator inserted between texts when joining them into a single batch. */
  val TextDelimiter: String = "\n---\n"

  /** Group texts into batches for translation, separated by a delimiter. */
  def prepareBatches(
      elements: Seq[TextElement],
      maxBatchSize: Int
  ): Seq[Batch] =
    elements.grouped(maxBatchSize).toSeq.zipWithIndex.map { (group, idx) =>
      val text = group.map(_.originalText).mkString(TextDelimiter)
      Batch(idx, text, group.toList)
    }

  /** Build a lookup map from TextElement location fields to translated text.
    *
    * Shared by all format services to reduce duplication in writeTranslated.
    */
  def buildLookup(
      elements: Seq[TextElement]
  ): Map[(Int, Int, Option[Int], Int, Int), String] =
    elements.map(e =>
      (e.slideIndex, e.shapeIndex, e.tableRowIndex, e.paragraphIndex, e.runIndex) -> e.translatedText
    ).toMap

  /** Registry of all known document formats, keyed by file extension.
    *
    * Each format object participates via a `given DocumentFormat` instance.
    */
  val formatRegistry: Map[String, DocumentFormat] =
    Seq(PptxService, DocxService, XlsService, PdfService)
      .flatMap(f => f.extensions.map(_ -> f))
      .toMap

  /** Look up a DocumentFormat by file extension. */
  def formatForExtension(ext: String): Option[DocumentFormat] =
    formatRegistry.get(ext.toLowerCase)
