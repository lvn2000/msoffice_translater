package service

import model.TextElement
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.{PDDocument, PDPage, PDPageContentStream}
import org.apache.pdfbox.pdmodel.font.{PDType0Font, PDType1Font, PDFont, Standard14Fonts}
import org.apache.pdfbox.text.PDFTextStripper

import java.io.{ByteArrayOutputStream, File}
import scala.util.{Try, Using}

/** Handles reading text from and writing translated text back to pdf files.
  *
  * Text is extracted line-by-line per page and sent for translation.
  * The translated text is then **overlaid** on the original PDF using
  * AppendMode.APPEND — all images, graphics, and original layout
  * are preserved. The original text remains underneath.
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
          val lines = pageText.split("\n").map(_.trim).filter(_.nonEmpty)

          lines.zipWithIndex.map { (line, li) =>
            TextElement(
              slideIndex     = pageIndex,
              shapeIndex     = li,
              tableRowIndex  = None,
              paragraphIndex = 0,
              runIndex       = 0,
              originalText   = line
            )
          }
        }
      }
    }.toEither.left.map(e => s"Failed to read pdf: ${e.getMessage}")

  override def writeTranslated(
      source: File,
      elements: Seq[TextElement],
      outputFile: File,
      langCode: String
  ): Either[String, Unit] =
    val byPage: Map[Int, Seq[TextElement]] = elements.groupBy(_.slideIndex)

    // Create a new PDF with only the translated text.
    // Original images, graphics and layout are not preserved.
    val result = Try {
      Using.resource(Loader.loadPDF(source)) { srcDoc =>
        val destDoc = new PDDocument()
        try
          val font = loadUnicodeFont(destDoc)

          for pageIdx <- 0 until srcDoc.getNumberOfPages do
            val srcPage  = srcDoc.getPage(pageIdx)
            val mediaBox = srcPage.getMediaBox
            val destPage = new PDPage(mediaBox)
            destDoc.addPage(destPage)

            val lines = byPage.getOrElse(pageIdx, Seq.empty).sortBy(_.shapeIndex)

            val stream = new PDPageContentStream(destDoc, destPage)
            stream.setFont(font, FontSize)
            stream.beginText()
            stream.newLineAtOffset(Margin, mediaBox.getHeight - Margin)

            var y = mediaBox.getHeight - Margin
            for elem <- lines do
              val text = if elem.translatedText.nonEmpty then elem.translatedText else elem.originalText
              if y - LineHeight >= Margin then
                try
                  stream.showText(text)
                  y -= LineHeight
                  stream.newLineAtOffset(0, -LineHeight)
                catch case _: Exception =>
                  () // skip lines the font cannot render

            stream.endText()
            stream.close()

          val bos = new ByteArrayOutputStream()
          destDoc.save(bos)
          bos.close()
          java.nio.file.Files.write(outputFile.toPath, bos.toByteArray)
        finally
          destDoc.close()
      }
    }.toEither.left.map(e => s"Failed to write pdf: ${e.getMessage}").map(_ => ())

    if result.isLeft && outputFile.exists && outputFile.length == 0 then
      outputFile.delete()

    result

  // --------------------------------------------------------------------------
  // Private helpers
  // --------------------------------------------------------------------------

  /** Find and load a Unicode-capable TrueType font, falling back to Helvetica. */
  private def loadUnicodeFont(doc: PDDocument): PDFont =
    val candidates = Seq(
      "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
      "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
      "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf",
      "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
      "/usr/share/fonts/truetype/droid/DroidSans.ttf",
      "/System/Library/Fonts/Supplemental/Arial.ttf",
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
