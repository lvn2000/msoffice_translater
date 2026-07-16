package model

/** A batch of text elements to translate together in one API call. */
case class Batch(index: Int, combinedText: String, elements: List[TextElement])
