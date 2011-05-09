package org.dbpedia.extraction.mappings

import org.dbpedia.extraction.wikiparser._
import org.dbpedia.extraction.destinations.{Graph, Quad, Dataset, IriRef, PlainLiteral}
import util.control.Breaks._
import java.io.FileNotFoundException
import java.lang.StringBuffer
import xml.{XML, Node => XMLNode}
import collection.mutable.{HashMap, Stack, ListBuffer, Set}

//some of my utilities
import MyNodeList._
import MyNode._
import TimeMeasurement._
import VarBinder._
import WiktionaryLogging._

/**
 * parses (wiktionary) wiki pages
 * is meant to be configurable for multiple languages
 *
 * is even meant to be usable for non-wiktionary wikis -> arbitrary wikis, but where all pages follow a common schema
 * but in contrast to infobox-focused extraction, we *aim* to be more flexible:
 * dbpedia core is hardcoded extraction. here we try to use a meta-language describing the information to be extracted
 * this is done via xml containing wikisyntax snippets (called templates) containing placeholders (called variables), which are then bound
 *
 * we also extended this approach to match the wiktionary schema
 * a page can contain information about multiple entities (sequential blocks), each having multiple contexts/senses
 * other use cases (non-wiktionary), can be seen as a special case, having only one block (entity) and one sense
 *
 * @author Jonas Brekle <jonas.brekle@gmail.com>
 * @author Sebastian Hellmann <hellmann@informatik.uni-leipzig.de>
 */

class WiktionaryPageExtractor(val language : String) extends Extractor {
  private val possibleLanguages = Set("en", "de")
  require(possibleLanguages.contains(language))

  //load config from xml
  private val config = XML.loadFile("config-"+language+".xml")

  private val templates = (config \ "templates" \ "sections" \ "template").map((n : XMLNode) =>  Tpl.fromNode(n))

  private val mappings = (config \ "mappings" \ "mapping").map(
      (n : XMLNode) =>
        ( (n \ "@from").text,
          if(n.attribute("toType").isDefined && (n \ "@toType").text.equals("uri")){new IriRef((n \ "@to").text)} else {new PlainLiteral((n \ "@to").text)}
        )
      ).toMap

  val ns =            (((config \ "properties" \ "property").find( {n : XMLNode => (n \ "@name").text.equals("ns") }).getOrElse(<propery uri="http://undefined.com/"/>)) \ "@value").text
  val blockProperty = (((config \ "properties" \ "property").find( {n : XMLNode => (n \ "@name").text.equals("blockProperty") }).getOrElse(<propery uri="http://undefined.com/"/>)) \ "@value").text
  val senseProperty = (((config \ "properties" \ "property").find( {n : XMLNode => (n \ "@name").text.equals("senseProperty") }).getOrElse(<propery uri="http://undefined.com/"/>)) \ "@value").text

  val wiktionaryDataset : Dataset = new Dataset("wiktionary")
  val tripleContext = new IriRef(ns)

  //to cache last used blockIris
  val blockIris = new HashMap[Block, IriRef]

  override def extract(page: PageNode, subjectUri: String, pageContext: PageContext): Graph =
  {
    // wait a random number of seconds. kills parallelism - otherwise debug output from different threads is mixed
    //TODO remove if in production
    val r = new scala.util.Random
    Thread sleep r.nextInt(10)*1000

    val quads = new ListBuffer[Quad]()
    val word = subjectUri.split("/").last
    measure {

      val pageConfig = Page.fromNode((config \ "page").head)
      blockIris(pageConfig) = new IriRef(subjectUri)

      val pageStack =  new Stack[Node]().pushAll(page.children.reverse)

      // val allBlocksFlat = List(pageConfig) ++ (config \ "page" \\ "block").map(Block.fromNode(_))
      val curOpenBlocks = new ListBuffer[Block]()
      curOpenBlocks append pageConfig

      val proAndEpilogBindings : ListBuffer[Tuple2[Tpl, VarBindingsHierarchical]] = new ListBuffer

      //handle prolog (beginning) (e.g. "see also") - not related to blocks, but to the main entity of the page
      for(prolog <- config \ "templates" \ "prologs" \ "template"){
        val prologtpl = Tpl.fromNode(prolog)
         try {
          proAndEpilogBindings.append( (prologtpl, parseNodesWithTemplate(prologtpl.tpl, pageStack)) )
        } catch {
          case e : WiktionaryException => proAndEpilogBindings.append( (prologtpl, e.vars) )
        }
      }

      //handle epilog (ending) (e.g. "links to other languages") by parsing the page backwards
      val rev = new Stack[Node] pushAll pageStack //reversed
      for(epilog <- config \ "templates" \ "epilogs" \ "template"){
        val epilogtpl = Tpl.fromNode(epilog)
        try {
          proAndEpilogBindings.append( (epilogtpl, parseNodesWithTemplate(epilogtpl.tpl, rev)) )
        } catch {
          case e : WiktionaryException => proAndEpilogBindings.append( (epilogtpl, e.vars) )
        }
      }
      //apply consumed nodes (from the reversed page) to pageStack  (unreversed)
      pageStack.clear
      pageStack pushAll rev

      //handle the bindings from pro- and epilog
      proAndEpilogBindings.foreach({case (tpl : Tpl, tplBindings : VarBindingsHierarchical) => {
         quads appendAll handleBlockBinding(pageConfig, tpl, tplBindings)
      }})

      var consumed = false
      while(pageStack.size > 0){
        //Thread sleep 500
        // try recognizing block starts of blocks. if recognized we go somewhere UP the hierarchy (the block ended) or one step DOWN (new sub block)
        // each block has a "indicator-template" (indTpl)
        // when it matches, the block starts. and from that template we get bindings that describe the block

        //debug: print the page (the next 2 nodes only)
        //println()

        consumed = false
        for(block <- curOpenBlocks ++ (if(curOpenBlocks.last.blocks.isDefined){List[Block](curOpenBlocks.last.blocks.get)} else {List[Block]()})){
          if(block.indTpl == null){
            //continue - the "page" block has no indicator tpl, it starts implicitly with the page
          } else {
            println(pageStack.take(1).map(_.dumpStrShort).mkString)
            try {
              //println("vs")
              //println(block.indTpl.tpl.map(_.dumpStrShort).mkString )
              val blockIndBindings =  parseNodesWithTemplate(block.indTpl.tpl.clone, pageStack)
              //no exception -> success -> stuff below here will be executed on success
              println("successfully recognized block start "+block.indTpl.name)
              consumed = true
              val newOpen = curOpenBlocks.takeWhile((cand : Block) => !cand.eq(block))
              if(newOpen.size == curOpenBlocks.size){
                //one step down/deeper

                //build a uri for the block
                val blockIdentifier = new StringBuffer(blockIris(curOpenBlocks.last).uri)
                block.indTpl.vars.foreach((varr : Var) => {
                  //concatenate all binding values of the block indicator tpl (sufficient?)
                  blockIdentifier append "-"+blockIndBindings.getFirstBinding(varr.name).getOrElse(List()).myToString
                })
                val blockIri = new IriRef(blockIdentifier.toString)
                blockIris(block) = blockIri
                println("new block "+blockIdentifier.toString)
                //generate triples that indentify the block
                block.indTpl.vars.foreach((varr : Var) => {
                  val objStr = blockIndBindings.getFirstBinding(varr.name).getOrElse(List()).myToString
                  val obj = if(varr.doMapping){mappings.getOrElse(objStr,new PlainLiteral(objStr))} else {new PlainLiteral(objStr)}
                  quads += new Quad(wiktionaryDataset, blockIri, new IriRef(varr.property), obj, tripleContext)
                })

                //generate a triple that connects the last block to the new block
                quads += new Quad(wiktionaryDataset, blockIris(curOpenBlocks.last), new IriRef(curOpenBlocks.last.blocks.get.property), blockIri, tripleContext)
                curOpenBlocks append curOpenBlocks.last.blocks.get
              } else {
                curOpenBlocks.clear()
                curOpenBlocks.appendAll(newOpen) // up
              }
            } catch {
              case e : WiktionaryException => //did not match
            }
          }
        }

        val curBlock = curOpenBlocks.last
        //try matching this blocks templates
        for(tpl <- curBlock.templates){
          println(pageStack.take(1).map(_.dumpStrShort).mkString)
          try {
            //println("vs")
            //println(block.indTpl.tpl.map(_.dumpStrShort).mkString )
            val blockBindings =  parseNodesWithTemplate(tpl.tpl.clone, pageStack)
            //no exception -> success -> stuff below here will be executed on success
            println("successfully extracted data")
            consumed = true
            //generate triples
            //println(tpl.name +": "+ blockBindings.dump())
            quads appendAll handleBlockBinding(curBlock, tpl, blockBindings)
          } catch {
            case e : WiktionaryException => //did not match
          }
        }

        if(!consumed){
          pageStack.pop
        }
      }

    } report {
      duration : Long => println("took "+ duration +"ms")
    }
    println(""+quads.size+" quads extracted for "+word)
    quads.foreach((q : Quad) => println(q.renderNTriple))
    new Graph(quads.sortWith((q1, q2)=> q1.subject.uri.length < q2.subject.uri.length).toList)
  }

  def handleBlockBinding(block : Block, tpl : Tpl, blockBindings : VarBindingsHierarchical) : List[Quad] = {
    val quads = new ListBuffer[Quad]
    if(tpl.needsPostProcessing){
      //TODO does not work yet, implement the invocation of a static method that does a transformation of the bindings
      val clazz = ClassLoader.getSystemClassLoader().loadClass(tpl.ppClass.get)
      val method = clazz.getDeclaredMethod(tpl.ppMethod.get, null);
      val ret = method.invoke(blockBindings, null)
      quads ++= ret.asInstanceOf[List[Quad]]
    } else {
      //generate a triple for each var binding
      tpl.vars.foreach((varr : Var) => {
        if(varr.senseBound){
          //handle sense bound vars (e.g. meaning)
          //TODO use getAllSenseBoundVarBindings function
          val bindings = blockBindings.getSenseBoundVarBinding(varr.name)
          bindings.foreach({case (sense, binding) =>
            //the sense identifier is mostly something like "[1]" - sense is then List(TextNode("1"))
            val objStr = binding.myToString
            val obj = if(varr.doMapping){mappings.getOrElse(objStr,new PlainLiteral(objStr))} else {new PlainLiteral(objStr)}
            quads += new Quad(wiktionaryDataset, new IriRef(blockIris(block).uri + "-"+sense.myToString), new IriRef(varr.property), obj, tripleContext)
            //TODO triples to connect blocks to its senses (maybe collect all senses here, make distinct, then build triples after normal bindingtriples)
          })
        } else {
          //handle non-sense bound vars - they are related to the whole block/usage (e.g. hyphenation)
          val bindings = blockBindings.getAllBindings(varr.name)
          for(binding <- bindings){
            val objStr = binding.myToString
            val obj = if(varr.doMapping){mappings.getOrElse(objStr,new PlainLiteral(objStr))} else {new PlainLiteral(objStr)}
            quads += new Quad(wiktionaryDataset, blockIris(block), new IriRef(varr.property), obj, tripleContext)
          }
        }
      })
    }
    quads.toList
  }
}
