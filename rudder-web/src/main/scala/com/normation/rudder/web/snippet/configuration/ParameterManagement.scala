/*
*************************************************************************************
* Copyright 2013 Normation SAS
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
package com.normation.rudder.web.snippet.configuration

import net.liftweb.common._
import net.liftweb.http.DispatchSnippet
import net.liftweb.util._
import net.liftweb.util.Helpers._
import scala.xml._
import net.liftweb.http._
import net.liftweb.http.SHtml._
import net.liftweb.http.js._
import net.liftweb.http.js.JsCmds._
import bootstrap.liftweb.RudderConfig
import com.normation.rudder.domain.parameters.GlobalParameter
import com.normation.rudder.domain.parameters.ParameterName
import com.normation.eventlog.ModificationId
import org.joda.time.DateTime
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.web.model.{
  WBTextField, FormTracker, WBTextAreaField, WBRadioField
}
import java.util.regex.Pattern
import net.liftweb.http.js.JE.JsRaw
import com.normation.rudder.web.components.popup.CreateOrUpdateGlobalParameterPopup
import net.liftweb.http.js.JE.JsVar
import com.normation.rudder.domain.workflows.ChangeRequestId
import com.normation.rudder.web.model.JsInitContextLinkUtil

class ParameterManagement extends DispatchSnippet with Loggable {

  private[this] val roParameterService = RudderConfig.roParameterService

  private[this] val gridName      = "globalParametersGrid"
  private[this] val gridContainer = "ParamGrid"
  private[this] val htmlId_form   = "globalParameterForm"

  // For the deletion popup
  private[this] val woParameterService = RudderConfig.woParameterService
  private[this] val uuidGen            = RudderConfig.stringUuidGenerator
  private[this] val userPropertyService= RudderConfig.userPropertyService
  private[this] val workflowEnabled    = RudderConfig.configService.rudder_workflow_enabled

  //the current GlobalParameterForm component
  private[this] val parameterPopup = new LocalSnippet[CreateOrUpdateGlobalParameterPopup]

  def dispatch = {
    case "display" => { _ =>  display }
  }

  def display() : NodeSeq = {
    (for {
      seq <- roParameterService.getAllGlobalParameters
      workflowEnabled <- RudderConfig.configService.rudder_workflow_enabled
    } yield {
      (seq, workflowEnabled)
    }) match {
      case Full((seq, workflowEnabled)) => displayGridParameters(seq, gridName, workflowEnabled)
      case eb:EmptyBox =>
        val e = eb ?~! "Error when trying to get global Parameters"
        logger.error(s"Error when trying to display global Parameters, casue is: ${e.messageChain}")
        <div class="error">{e.msg}</div>
    }
  }

  def displayGridParameters(params:Seq[GlobalParameter], gridName:String, workflowEnabled: Boolean) : NodeSeq  = {
    (
      "tbody *" #> ("tr" #> params.map { param =>
        val lineHtmlId = Helpers.nextFuncName
        ".parameterLine [jsuuid]" #> lineHtmlId &
        ".parameterLine [class]" #> Text("curspoint") &
        ".name *" #> <b>{param.name.value}</b> &
        ".value *" #> <pre>{param.value}</pre> &
        ".description *" #> <span><ul class="evlogviewpad"><li><b>Description:</b> {Text(param.description)}</li></ul></span> &
        ".description [id]" #> ("description-" + lineHtmlId) &
        ".overridable *" #> param.overridable &
        ".change *" #> <div class="tw-bs">{
                       ajaxButton("Edit", () => showPopup("save", Some(param), workflowEnabled), ("class", "btn btn-default btn-xs"), ("style", "min-width:50px;")) ++
                       ajaxButton("Delete", () => showPopup("delete", Some(param), workflowEnabled), ("class", "btn btn-danger btn-xs"), ("style", "margin-left:5px;min-width:0px;"))
                       }</div>
      }) &
      ".createParameter *" #> ajaxButton("Add Parameter", () => showPopup("create", None, workflowEnabled) , ("class","btn btn-success new-icon space-bottom space-top"))
     ).apply(dataTableXml(gridName)) ++ Script(initJs)
  }



  private[this] def dataTableXml(gridName:String) = {
    <div id={gridContainer}>
      <div id="actions_zone">
        <div class="createParameter tw-bs"/>
      </div>
      <table id={gridName} class="display" cellspacing="0">
        <thead>
          <tr class="head">
            <th>Name</th>
            <th>Value</th>
            <th>Change</th>
          </tr>
        </thead>

        <tbody>
          <tr class="parameterLine">
            <td class="name listopen">[name of parameter]</td>
            <td class="value">[value of parameter]</td>
            <div class="description" style="display:none;" >[description]</div>
            <td class="change">[change / delete]</td>
          </tr>
        </tbody>
      </table>

      <div id="parametersGrid_paginate_area" class="paginate"></div>

    </div>

  }

  private[this] def jsVarNameForId(tableId:String) = "oTable" + tableId

  private[this] def initJs() : JsCmd = {
    OnLoad(
        JsRaw(s"""
          /* Event handler function */
          ${jsVarNameForId(gridName)} = $$('#${gridName}').dataTable({
            "asStripeClasses": [ 'color1', 'color2' ],
            "bAutoWidth"   : false,
            "bFilter"      : true,
            "bPaginate"    : true,
            "bLengthChange": true,
            "bStateSave"   : true,
                    "fnStateSave": function (oSettings, oData) {
                      localStorage.setItem( 'DataTables_${gridName}', JSON.stringify(oData) );
                    },
                    "fnStateLoad": function (oSettings) {
                      return JSON.parse( localStorage.getItem('DataTables_${gridName}') );
                    },
            "sPaginationType": "full_numbers",
            "oLanguage": {
              "sZeroRecords": "No parameters!",
              "sSearch": ""
            },
            "bJQueryUI"    : true,
            "aaSorting"    : [[ 0, "asc" ]],
            "aoColumns": [
              { "sWidth": "300px" },
              { "sWidth": "600px" },
              { "sWidth": "140px" }
            ],
            "sDom": '<"dataTables_wrapper_top"fl>rt<"dataTables_wrapper_bottom"ip>',
            "lengthMenu": [ [10, 25, 50, 100, 500, 1000, -1], [10, 25, 50, 100, 500, 1000, "All"] ],
            "pageLength": 25
          });
          $$('.dataTables_filter input').attr("placeholder", "Filter");
          """
        ) &
        JsRaw("""
        /* Formating function for row details */
          function fnFormatDetails(id) {
            var sOut = '<span id="'+id+'" class="parametersDescriptionDetails"/>';
            return sOut;
          };

          $(#table_var#.fnGetNodes() ).each( function () {
            $(this).click( function (event) {
              var jTr = $(this);
              var opened = jTr.prop("open");
              var source = event.target || event.srcElement;
              if (!( $(source).is("button"))) {
                if (opened && opened.match("opened")) {
                  jTr.prop("open", "closed");
                  $(this).find("td.listclose").removeClass("listclose").addClass("listopen");
                  #table_var#.fnClose(this);
                } else {
                  jTr.prop("open", "opened");
                  $(this).find("td.listopen").removeClass("listopen").addClass("listclose");
                  var jsid = jTr.attr("jsuuid");
                  var color = 'color1';
                  if(jTr.hasClass('color2'))
                    color = 'color2';
                  var row = $(#table_var#.fnOpen(this, fnFormatDetails(jsid), 'details'));
                  $(row).addClass(color + ' parametersDescription');
                  $('#'+jsid).html($('#description-'+jsid).html());
                }
               }
            } );
          })


      """.replaceAll("#table_var#",jsVarNameForId(gridName))
      )
    )
  }

  private[this] def showPopup(
      action         : String
    , parameter      : Option[GlobalParameter]
    , workflowEnabled: Boolean
  ) : JsCmd = {
    parameterPopup.set(
        Full(
            new CreateOrUpdateGlobalParameterPopup(
                parameter
              , workflowEnabled
              , action
              , cr => workflowCallBack(action, workflowEnabled)(cr)
            )
        )
      )
    val popupHtml = createPopup
    SetHtml(CreateOrUpdateGlobalParameterPopup.htmlId_popupContainer, popupHtml) &
    JsRaw( """ createPopup("%s",300,600) """.format(CreateOrUpdateGlobalParameterPopup.htmlId_popup))

  }


  private[this] def workflowCallBack(action:String, workflowEnabled: Boolean)(returns : Either[GlobalParameter,ChangeRequestId]) : JsCmd = {
    if ((!workflowEnabled) & (action == "delete")) {
      closePopup() & onSuccessDeleteCallback(workflowEnabled)
    } else {
      returns match {
        case Left(param) => // ok, we've received a parameter, do as before
          closePopup() &  updateGrid(workflowEnabled) & successPopup
        case Right(changeRequestId) => // oh, we have a change request, go to it
          JsInitContextLinkUtil.redirectToChangeRequestLink(changeRequestId)
      }
    }
  }

  /**
    * Create the creation popup
    */
  def createPopup : NodeSeq = {
    parameterPopup.get match {
      case Failure(m,_,_) =>  <span class="error">Error: {m}</span>
      case Empty => <div>The component is not set</div>
      case Full(popup) => popup.popupContent()
    }
  }



  private[this] def updateGrid(workflowEnabled: Boolean) : JsCmd = {
    Replace(gridContainer, display())
  }

  ///////////// success pop-up ///////////////
  private[this] def successPopup : JsCmd = {
    JsRaw(""" callPopupWithTimeout(200, "successConfirmationDialog", 100, 350)
    """)
  }

  private[this] def onSuccessDeleteCallback(workflowEnabled: Boolean) : JsCmd = {
    updateGrid(workflowEnabled) & successPopup
  }



  private[this] def closePopup() : JsCmd = {
    JsRaw(""" $('.modal').bsModal('hide');""")
  }


}
