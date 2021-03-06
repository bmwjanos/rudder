/*
*************************************************************************************
* Copyright 2017 Normation SAS
*************************************************************************************
*
* This file is part of Rudder.
*
* Rudder is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU General Public License version 3, the copyright holders add
* the following Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
* Public License version 3, when you create a Related Module, this
* Related Module is not considered as a part of the work and may be
* distributed under the license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* Rudder is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

*
*************************************************************************************
*/

package com.normation.rudder.web.rest

import com.normation.rudder.web.rest.RestUtils._

import net.liftweb.common._
import net.liftweb.http.JsonResponse
import net.liftweb.http.js._
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsExp

import net.liftweb.http.rest.RestHelper
import net.liftweb.json.JArray
import net.liftweb.json.JsonAST._
import net.liftweb.json.JsonDSL._
import com.normation.rudder.services.quicksearch.FullQuickSearchService
import com.normation.rudder.services.quicksearch.QuickSearchResult
import com.normation.rudder.services.quicksearch.QuickSearchResultId
import com.normation.rudder.services.quicksearch.QSObject
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.authorization.Read
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.RoRuleRepository
import scala.collection.SortedSet
import com.normation.rudder.domain.policies.Tags
import com.normation.rudder.domain.policies.Tag

class RestCompletion(
  completion : RestCompletionService
) extends RestHelper with Loggable {

  serve {
    case Get("secure" :: "api" :: "completion" :: "tags" :: (kind @ ("rule" |"directive")) :: "key" :: token :: Nil, req) => {
      val fetchTags = if (kind == "directive") {
        completion.findDirectiveTagNames(token)
      } else {
        // rule
        completion.findRuleTagNames(token)
      }
      fetchTags match {
        case eb: EmptyBox  =>
          val e = eb ?~! s"Error when looking for object containing ${token}"
          toJsonError(None, e.messageChain)("quicksearch", false)

        case Full(results) =>
          logger.info(results)
          toJsonResponse(None, results.map(("value",_)))("completeTags", false)
      }

    }
    case Get("secure" :: "api" :: "completion" :: "tags" :: (kind @ ("rule" |"directive")) :: "value" :: token :: Nil, req) => {

      val fetchTags = if (kind == "directive") {
        completion.findDirectiveTagValues(token,None)
      } else {
        // rule
        completion.findRuleTagValues(token,None)
      }
      fetchTags match {
        case eb: EmptyBox  =>
          val e = eb ?~! s"Error when looking for object containing ${token}"
          toJsonError(None, e.messageChain)("quicksearch", false)

        case Full(results) =>
          logger.info(results)
          toJsonResponse(None, results.map(("value",_)))("completeTags", false)
      }

    }
    case Get("secure" :: "api" :: "completion" :: "tags" :: (kind @ ("rule" |"directive")) :: "value" :: key :: token :: Nil, req) => {
      val fetchTags = if (kind == "directive") {
        completion.findDirectiveTagValues(token,Some(key))
      } else {
        // rule
        completion.findRuleTagValues(token,Some(key))
      }
      fetchTags match {
        case eb: EmptyBox  =>
          val e = eb ?~! s"Error when looking for object containing ${token}"
          toJsonError(None, e.messageChain)("quicksearch", false)

        case Full(results) =>
          logger.warn(results)
          toJsonResponse(None, results.map(("value",_)))("completeTags", false)
      }

    }

  }

}

class RestCompletionService (
  readDirective : RoDirectiveRepository
, readRule : RoRuleRepository
) {
  def findDirectiveTagNames(matching : String) : Box[List[String]] = {
    for {
      lib <- readDirective.getFullDirectiveLibrary()
    } yield {
      (for {
      tag <- lib.allDirectives.flatMap(_._2._2.tags.tags).toList
      val name = tag.tagName.name
      if name.startsWith(matching)
      } yield {
        name
      }).sorted.distinct
   }
  }

  def findDirectiveTagValues(matching : String, tagName : Option[String]) : Box[List[String]] = {
    for {
      lib <- readDirective.getFullDirectiveLibrary()
    } yield {
      (for {
      tag <- lib.allDirectives.flatMap(_._2._2.tags.tags).toList
      if tagName.map(_ == tag.tagName.name).getOrElse(true)
      value = tag.tagValue.value
      if value.startsWith(matching)
      } yield {
        value
      }).sorted.distinct
   }
  }

  def findRuleTagNames(matching : String) : Box[List[String]] = {
    for {
      rules <- readRule.getAll(false)
    } yield {
      (for {
      tag <- rules.flatMap(_.tags.tags).toList
      if tag.tagName.name.contains(matching)
      } yield {
        tag.tagName.name
      }).sorted.distinct
    }
  }

  def findRuleTagValues(matching : String, tagName : Option[String]) : Box[List[String]] = {
    for {
      rules <- readRule.getAll(false)
    } yield {
      (for {
      tag <- rules.flatMap(_.tags.tags).toList
      if tagName.map(_ == tag.tagName.name).getOrElse(true)
      value = tag.tagValue.value
      if value.startsWith(matching)
      } yield {
        value
      }).sorted.distinct
    }
  }
}
