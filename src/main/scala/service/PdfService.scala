package service

import model.TextElement
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.{PDDocument, PDPage, PDPageContentStream}
import org.apache.pdfbox.pdmodel.font.{PDType0Font, PDType1Font, PDFont, Standard14Fonts}
import org.apache.pdfbox.text.PDFTextStripper

import java.io.{File, FileOutputStream}
import scala.util.{Try, Using}

/** Handles reading text from and writing translated text back to pdf files.
  *
  * PDF is a rendering format without a structured text-run model — this
  * service creates a *new* PDF with the translated text. Original graphics,
  * images, and precise layout are not preserved.
  */
object PdfService extends DocumentFormat:

  override val extensions: Seq[String] = Seq("pdf")

  private val FontSize   = 11f
  private val LineHeight = 15f
  private val Margin     = 50f

  override def extractTexts(file: File): Either[String, Seq[TextElement]] =
    Try {
      Using.resource(Loader.loadPDF(file)) { doc =>
        val totalPages = doc.getNumberOfPages

        (0 until totalPages).flatMap { pageIndex =>
          val stripper = new PDFTextStripper()
          stripper.setSortByPosition(true)
          stripper.setStartPage(pageIndex + 1)
          stripper.setEndPage(pageIndex + 1)

          val pageText = stripper.getText(doc)
          val paragraphs = pageText.split("\n\n+").map(_.trim).filter(_.nonEmpty)

          paragraphs.zipWithIndex.map { (para, pi) =>
            TextElement(
              slideIndex     = pageIndex,
              shapeIndex     = pi,
              tableRowIndex  = None,
              paragraphIndex = 0,
              runIndex       = 0,
              originalText   = para
            )
          }
        }
      }
    }.toEither.left.map(e => s"Failed to read pdf: ${e.getMessage}")

  override def writeTranslated(
      source: File,
      elements: Seq[TextElement],
      outputFile: File
  ): Either[String, Unit] =
    val byPage: Map[Int, Seq[TextElement]] = elements.groupBy(_.slideIndex)

    Try {
      Using.resources(
        Loader.loadPDF(source),
        new FileOutputStream(outputFile)
      ) { (srcDoc, out) =>
        val destDoc = new PDDocument()
        try
          val font = loadUnicodeFont(destDoc)

          for pageIdx <- 0 until srcDoc.getNumberOfPages do
            val srcPage  = srcDoc.getPage(pageIdx)
            val mediaBox = srcPage.getMediaBox
            val destPage = new PDPage(mediaBox)
            destDoc.addPage(destPage)

            val paragraphs = byPage.getOrElse(pageIdx, Seq.empty)

            val stream = new PDPageContentStream(destDoc, destPage)
            stream.setFont(font, FontSize)
            stream.beginText()
            stream.newLineAtOffset(Margin, mediaBox.getHeight - Margin)

            var y = mediaBox.getHeight - Margin
            for elem <- paragraphs do
              val text  = if elem.translatedText.nonEmpty then elem.translatedText else elem.originalText
              val lines = text.split("\n")
              for line <- lines do
                if y - LineHeight >= Margin then
                  stream.showText(line)
                  y -= LineHeight
                  stream.newLineAtOffset(0, -LineHeight)
              if y - LineHeight >= Margin then
                y -= LineHeight
                stream.newLineAtOffset(0, -LineHeight)

            stream.endText()
            stream.close()

          destDoc.save(out)
        finally
          destDoc.close()
      }
    }.toEither.left.map(e => s"Failed to write pdf: ${e.getMessage}")

  // --------------------------------------------------------------------------
  // Private helpers
  // --------------------------------------------------------------------------

  /** Find and load a Unicode-capable TrueType font, falling back to Helvetica. */
  private def loadUnicodeFont(doc: PDDocument): PDFont =
    val candidates = Seq(
      "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
      "/usr/share/fonts/truetype/droid/DroidSans.ttf",
      "/System/Library/Fonts/Helvetica.ttc",
      "/Library/Fonts/Arial.ttf",
      "C:\\Windows\\Fonts\\arial.ttf"
    )
    candidates.find(p => new java.io.File(p).exists()) match
      case Some(path) =>
        try PDType0Font.load(doc, new java.io.File(path))
        catch case _: Exception => new PDType1Font(Standard14Fonts.FontName.HELVETICA)
      case None => new PDType1Font(Standard14Fonts.FontName.HELVETICA)

end PdfService
