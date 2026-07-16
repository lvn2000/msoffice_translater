package service

import model.TextElement
import java.io.File

/** Type class for document formats that can be translated.
  *
  * Each format (pptx, docx, xls, pdf) provides a given instance
  * that defines which file extensions it handles and how to
  * extract/write text.
  */
trait DocumentFormat:

  /** File extensions this format handles (e.g. "pptx", "docx", "xls"). */
  def extensions: Seq[String]

  /** Extract all text elements from a file.
    *
    * @return Right(elements) on success, Left(error) on failure
    */
  def extractTexts(file: File): Either[String, Seq[TextElement]]

  /** Write translated texts into a copy of the source file.
    *
    * @return Right(()) on success, Left(error) on failure
    */
  def writeTranslated(
      source: File,
      elements: Seq[TextElement],
      outputFile: File
  ): Either[String, Unit]
