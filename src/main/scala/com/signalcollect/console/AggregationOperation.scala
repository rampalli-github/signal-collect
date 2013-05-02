/*
 *  @author Carol Alexandru
 *  
 *  Copyright 2013 University of Zurich
 *      
 *  Licensed below the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  
 *         http://www.apache.org/licenses/LICENSE-2.0
 *  
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed below the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations below the License.
 *  
 */

package com.signalcollect.console

import scala.language.postfixOps
import com.signalcollect.interfaces.AggregationOperation
import com.signalcollect.interfaces.ModularAggregationOperation
import net.liftweb.json._
import net.liftweb.json.JsonDSL._
import com.signalcollect.TopKFinder
import com.signalcollect.Edge
import com.signalcollect.Vertex
import com.signalcollect.interfaces.Inspectable
import BreakConditionName._

/** Aggregator that loads a JObject representation of vertices and their edges.
  *
  * Given the set of ids, the aggregator the corresponding vertices and the 
  * edges between the nodes. The aggregator returs a JObject, which contains
  * two objects, one for nodes, one for edges. The data structure is best
  * explained by an example:
  *
  * {{{
  * {"nodes":{"id1":{"s":"0.15","ss":0.0,"cs":1.0},
  *           "id2":{"s":"0.16","ss":1.0,"cs":1.0},
  *           "id3":{"s":"0.17","ss":1.0,"cs":1.0}},
  *  "edges":{"id1":["id2","id3"]}}}
  * }}}
  *
  * The nodes object uses a node id as key and stores the state, signal and 
  * collect scores. The edges object uses a node id as key and stores the list
  * of target nodes.
  *
  * @constructor create the aggregator
  * @param nodeIds set of node ids to be loaded
  */
class GraphAggregator[Id](nodeIds: Set[Id] = Set[Id]())
      extends AggregationOperation[JObject] {

  def extract(v: Vertex[_,_]): JObject = v match {
    case i: Inspectable[Id,_] => {
      if (nodeIds.contains(i.id)) {
        // Get the list of target nodes that this node's edges point at
        val targetNodes = i.outgoingEdges.values.filter { value =>
          // This match is necessary because only an Edge[Id] will have a 
          // targetId of type Id.
          value match {
            case v: Edge[Id] => nodeIds.contains(v.targetId)
            case otherwise => false
          }
        }.map{ e => ( JString(e.targetId.toString))}.toList
        def nodesObj = ("nodes", JObject(List(JField(i.id.toString, 
                          JObject(List(JField("s", i.state.toString),
                                       JField("ss", i.scoreSignal),
                                       JField("cs", i.scoreCollect)))))))
        def edgesObj = ("edges", JObject(List(JField(i.id.toString, JArray(targetNodes)))))
        if (targetNodes.size > 0) { nodesObj ~ edgesObj } else { nodesObj }
      }
      else { JObject(List()) }

      }
    case other => JObject(List())
  }

  def reduce(vertices: Stream[JObject]): JObject = {
    vertices.foldLeft(JObject(List())) { (acc, v) => 
      acc merge v
    }
  }
}


/** Aggregator that retrieves a random sample of node ids.
  *
  * @constructor create the aggregator
  * @param sampleSize the number of node ids to retrieve
  */
class SampleAggregator[Id](sampleSize: Int) 
      extends ModularAggregationOperation[Set[Id]] {

  val neutralElement = Set[Id]()

  def aggregate(a: Set[Id], b: Set[Id]): Set[Id] = {
    val combinedSet = a ++ b
    combinedSet.slice(0, math.min(sampleSize, combinedSet.size)).toSet
  }

  def extract(v: Vertex[_, _]): Set[Id] = v match {
    case i: Inspectable[Id, _] => 
      List(i.id).toSet
  }
}

/** Aggregator that retrieves nodes with the highest degree.
  *
  * The aggregator produces a map of node ids to degrees.
  *
  * @constructor create the aggregator
  * @param n the number of top elements to find
  */
class TopDegreeAggregator[Id](n: Int)
      extends AggregationOperation[Map[Id,Int]] {

  def extract(v: Vertex[_, _]): Map[Id,Int] = v match {
    case i: Inspectable[Id, _] => 
      // Create one map from this id to the number of outgoing edges
      Map(i.id -> i.outgoingEdges.size) ++
      // Create several maps, one for each target id to 1
      i.outgoingEdges.values.map { 
        case v: Edge[Id] => (v.targetId -> 1)
      }
    case other => Map[Id,Int]()
  }

  def reduce(degrees: Stream[Map[Id,Int]]): Map[Id,Int] = {
    // Combine the maps created above to count the total number of edges
    Toolkit.mergeMaps(degrees.toList)((v1, v2) => v1 + v2)
  }
}

/** Aggregator that retrieves nodes with the highest or lowest state.
  *
  * The aggregator produces a list of tuples, each containing the state and the
  * node id with that state.
  *
  * @constructor create the aggregator
  * @param n the number of top elements to find
  * @param inverted gather by lowest, not highest state
  */
class TopStateAggregator[Id](n: Int, inverted: Boolean)
      extends AggregationOperation[List[(Double,Id)]] {

  def extract(v: Vertex[_, _]): List[(Double,Id)] = v match {
    case i: Inspectable[Id, _] => 
      // Try to interpret different types of numberic states
      val state: Option[Double] = i.state match {
        case x: Double => Some(x)
        case x: Int => Some(x.toDouble)
        case x: Long => Some(x.toDouble)
        case x: Float => Some(x.toDouble)
        case otherwise => None
      }
      state match {
        case Some(number) => 
          List[(Double,Id)]((number, i.id))
        case otherwise => List[(Double,Id)]()
      }
    case otherwise => List[(Double,Id)]()
  }

  def reduce(degrees: Stream[List[(Double,Id)]]): List[(Double,Id)] = {
    degrees.foldLeft(List[(Double,Id)]()) { (acc, n) => acc ++ n }
           .sortWith({ (t1, t2) => 
             if (inverted) { t1._1 < t2._1 }
             else { t1._1 > t2._1 }
           })
           .take(n)
  }
}

/** Aggregator that retrieves nodes with the highest signal or collect scores.
  *
  * The aggregator produces a list of tuples, each containing the score and the
  * node id with that score.
  *
  * @constructor create the aggregator
  * @param n the number of top elements to find
  * @param scoreType the score to look at (signal or collect)
  */
class TopScoreAggregator[Id](n: Int, scoreType: String)
      extends AggregationOperation[List[(Double,Id)]] {

  def extract(v: Vertex[_, _]): List[(Double,Id)] = v match {
    case i: Inspectable[Id, _] => 
      val score = scoreType match {
        case "signal" => i.scoreSignal
        case "collect" => i.scoreCollect
      }
      List[(Double,Id)]((score, i.id))
    case otherwise => List[(Double,Id)]()
  }

  def reduce(degrees: Stream[List[(Double,Id)]]): List[(Double,Id)] = {
    degrees.foldLeft(List[(Double,Id)]()) { (acc, n) => acc ++ n }
           .sortWith({ (t1, t2) => t1._1 > t2._1 })
           .take(n)
  }

}

/** Aggregator that loads the ids of nodes in the vicinity of other nodes.
  *
  * The aggregator produces a new set of ids representing the nodes that are
  * connected to any of the nodes in the given set, be it incoming or outgoing.
  *
  * @constructor create the aggregator
  * @param ids set of node ids to be loaded
  */
class FindNodeVicinitiesByIdsAggregator[Id](ids: Set[Id])
      extends AggregationOperation[Set[Id]] {

  def extract(v: Vertex[_,_]): Set[Id] = v match {
    case i: Inspectable[Id,_] =>
      // If this node is the target of a primary node, it's a vicinity node
      if(i.outgoingEdges.values.view.map { 
        case v: Edge[Id] if (ids.contains(v.targetId)) => true
        case otherwise => false
      }.toSet.contains(true)) { return  Set(i.id) }
      // If this node is a primary node, all its targets are vicinity nodes
      if (ids.contains(i.id)) {
        return i.outgoingEdges.values.map{ case v: Edge[Id] => v.targetId }.toSet
      }
      // If neither is true, this node is irrelevant
      return Set()
    case otherwise => Set()
  }

  def reduce(vertices: Stream[Set[Id]]): Set[Id] = {
    vertices.toSet.flatten
  }
}

/** Aggregator that translates a list of strings to a list of vertices.
  *
  * The aggregator compares the string representation of the id of any node
  * to the strings supplied to it.
  *
  * @constructor create the aggregator
  * @param idsList the list of ids to compare node ids with
  */
class FindVerticesByIdsAggregator[Id](idsList: List[String])
      extends AggregationOperation[List[Vertex[Id,_]]] {

  def ids = idsList.toSet

  def extract(v: Vertex[_, _]): List[Vertex[Id,_]] = v match {
    case i: Inspectable[Id, _] => {
      if (ids.contains(i.id.toString)) { return List(i) }
      else { return List() }
    }
    case other => List()
  }

  def reduce(vertices: Stream[List[Vertex[Id,_]]]): List[Vertex[Id,_]] = {
    vertices.toList.flatten
  }

}

/** Aggregator that checks if any of the break conditions apply
  *
  * The aggregator takes a map of ids (strings used to identify break 
  * conditions) to BreakCondition items. It produces a map of the same ids to
  * strings which represent the reason for the condition firing. For example,
  * one result item may be ("3" -> "0.15"), which would mean that the condition
  * identified as "3" fired because of a value "0.15".
  *
  * @constructor create the aggregator
  * @param conditions map of conditions
  */
class BreakConditionsAggregator(conditions: Map[String,BreakCondition])
      extends AggregationOperation[Map[String,String]] {

  val nodeConditions = List(
    ChangesState,
    GoesAboveState,
    GoesBelowState,
    GoesAboveSignalThreshold,
    GoesBelowSignalThreshold,
    GoesAboveCollectThreshold,
    GoesBelowCollectThreshold
  )

  def extract(v: Vertex[_, _]): Map[String,String] = v match {
    case i: Inspectable[_, _] => {
      var results = Map[String,String]()
      conditions.foreach { case (id, c) => 
        if (nodeConditions.contains(c.name)) {
          if (i.id.toString == c.props("nodeId")) {
            c.name match { 
              case ChangesState =>
                if (i.state.toString != c.props("currentState"))
                  results += (id -> i.state.toString)
              case GoesAboveState =>
                if (i.state.toString.toDouble > c.props("expectedState").toDouble)
                  results += (id -> i.state.toString)
              case GoesBelowState =>
                if (i.state.toString.toDouble < c.props("expectedState").toDouble)
                  results += (id -> i.state.toString)
              case GoesBelowSignalThreshold =>
                if (i.scoreSignal < c.props("signalThreshold").toDouble)
                  results += (id -> i.scoreSignal.toString)
              case GoesAboveSignalThreshold =>
                if (i.scoreSignal > c.props("signalThreshold").toDouble)
                  results += (id -> i.scoreSignal.toString)
              case GoesBelowCollectThreshold =>
                if (i.scoreCollect < c.props("collectThreshold").toDouble)
                  results += (id -> i.scoreCollect.toString)
              case GoesAboveCollectThreshold =>
                if (i.scoreCollect > c.props("collectThreshold").toDouble)
                  results += (id -> i.scoreCollect.toString)
            }
          }
        }
      }
      results
    }
  }
  def reduce(results: Stream[Map[String,String]]): Map[String,String] = {
    Toolkit.mergeMaps(results.toList)((v1, v2) => v1 + v2)
  }
}

