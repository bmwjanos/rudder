package com.normation.rudder.web.components.popup

import com.normation.rudder.domain.nodes._
import net.liftweb.http.DispatchSnippet
import com.normation.utils.StringUuidGenerator
import com.normation.rudder.domain.policies.{ Rule, RuleId }
import net.liftweb.http.Templates
import org.slf4j.LoggerFactory
import net.liftweb.http.js._
import JsCmds._
import com.normation.utils.StringUuidGenerator
import com.normation.inventory.domain.NodeId
import JE._
import net.liftweb.common._
import net.liftweb.http.{ SHtml, DispatchSnippet, Templates }
import scala.xml._
import net.liftweb.util.Helpers._
import com.normation.rudder.web.model.{
  WBTextField, FormTracker, WBTextAreaField, WBSelectField, WBRadioField
}
import com.normation.rudder.repository._
import com.normation.rudder.domain.eventlog.AddRule
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.repository._
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.domain.queries.Query
import com.normation.rudder.web.services.UserPropertyService
import com.normation.eventlog.ModificationId
import bootstrap.liftweb.RudderConfig
import com.normation.rudder.web.ChooseTemplate

class CreateCloneGroupPopup(
  nodeGroup : Option[NodeGroup],
  groupGenerator : Option[NodeGroup] = None,
  onSuccessCategory : (NodeGroupCategory) => JsCmd,
  onSuccessGroup : (NodeGroup,NodeGroupCategoryId) => JsCmd,
  onSuccessCallback : (String) => JsCmd = { (String) => Noop },
  onFailureCallback : () => JsCmd = { () => Noop } )
  extends DispatchSnippet with Loggable {

  private[this] val roNodeGroupRepository = RudderConfig.roNodeGroupRepository
  private[this] val woNodeGroupRepository = RudderConfig.woNodeGroupRepository
  private[this] val nodeInfoService       = RudderConfig.nodeInfoService
  private[this] val uuidGen               = RudderConfig.stringUuidGenerator
  private[this] val userPropertyService   = RudderConfig.userPropertyService

  private[this] val categories = roNodeGroupRepository.getAllNonSystemCategories
  // Fetch the parent category, if any
  private[this] val parentCategoryId = nodeGroup.flatMap(x => roNodeGroupRepository.getParentGroupCategory(x.id)).map(_.id.value).getOrElse("")

  var createContainer = false

  def dispatch = {
    case "popupContent" => { _ => popupContent }
  }

  def popupContent() : NodeSeq = (
    SHtml.ajaxForm( (
      "item-itemname" #> piName.toForm_!
    & "item-itemcontainer" #> piContainer.toForm_!
    & "item-itemdescription" #> piDescription.toForm_!
    & "item-grouptype" #> piStatic.toForm_!
    & "item-itemreason" #> { piReasons.map { f =>
        <div>
          <h4 class="col-lg-12 col-sm-12 col-xs-12 audit-title">Change Audit Log</h4>
          {f.toForm_!}
        </div>
      } }
    & "item-notifications" #> updateAndDisplayNotifications(formTracker)
    & "item-cancel" #> (SHtml.ajaxButton( "Cancel", { () => closePopup() } ) % ( "tabindex", "6" )% ( "class", "btn btn-default" ) )
    & "item-save" #> (SHtml.ajaxSubmit( "Clone", onSubmit _ ) % ( "id", "createCOGSaveButton" ) % ( "tabindex", "5" )% ( "class", "btn btn-success" ) )
    )(popupTemplate) ) ++ Script( OnLoad( initJs ) )
  )


  def popupTemplate = ChooseTemplate(
      List("templates-hidden", "Popup", "createCloneGroupPopup")
    , "groups:createclonegrouppopup"
  )

  private[this] def closePopup() : JsCmd = {
    JsRaw(""" $('#createCloneGroupPopup').bsModal('hide');""")
  }

  private[this] def onFailure() : JsCmd = {
    onFailureCallback() &
    updateFormClientSide()
  }
//    private[this] def onFailure : JsCmd = {
//    formTracker.addFormError(error("There was problem with your request"))
//    updateFormClientSide()
//  }

  private[this] def error(msg:String) = <span class="col-lg-12 errors-container">{msg}</span>

  private[this] def onSubmit() : JsCmd = {
    if(formTracker.hasErrors) {
      onFailure()
    } else {
      // get the type of query :
      if (createContainer) {
        woNodeGroupRepository.addGroupCategorytoCategory(
            new NodeGroupCategory(
              NodeGroupCategoryId(uuidGen.newUuid),
              piName.get,
              piDescription.get,
              Nil,
              Nil
            )
          , NodeGroupCategoryId(piContainer.get)
          , ModificationId(uuidGen.newUuid)
          , CurrentUser.getActor
          , Some("Node Group category created by user from UI")
        ) match {
          case Full(x) => closePopup() & onSuccessCallback(x.id.value) & onSuccessCategory(x)
          case Empty =>
            logger.error("An error occurred while saving the category")
            formTracker.addFormError(error("An error occurred while saving the category"))
            onFailure
          case Failure(m,_,_) =>
            logger.error("An error occurred while saving the category:" + m)
            formTracker.addFormError(error(m))
            onFailure
        }
      } else {
        // we are creating a group
        val query = nodeGroup.map(x => x.query).getOrElse(groupGenerator.flatMap(_.query))
        val parentCategoryId = NodeGroupCategoryId(piContainer.get)
        val isDynamic = piStatic.get match { case "dynamic" => true ; case _ => false }
        val srvList =  nodeGroup.map(x => x.serverList).getOrElse(Set[NodeId]())
        val nodeId = NodeGroupId(uuidGen.newUuid)
        val clone = NodeGroup(nodeId,piName.get,piDescription.get,query,isDynamic,srvList,true)

        woNodeGroupRepository.create(
            clone
          , parentCategoryId
          , ModificationId(uuidGen.newUuid)
          , CurrentUser.getActor
          , piReasons.map(_.get)
        ) match {
          case Full(x) =>
            closePopup() &
            onSuccessCallback(x.group.id.value) &
            onSuccessGroup(x.group, parentCategoryId)
          case Empty =>
            logger.error("An error occurred while saving the group")
            formTracker.addFormError(error("An error occurred while saving the group"))
            onFailure
          case Failure(m,_,_) =>
            logger.error("An error occurred while saving the group:" + m)
            formTracker.addFormError(error(m))
            onFailure
        }
      }
    }
  }

  private[this] def updateAndDisplayNotifications(formTracker : FormTracker) : NodeSeq = {

    val notifications = formTracker.formErrors
    formTracker.cleanErrors

    if(notifications.isEmpty) {
      NodeSeq.Empty
    }
    else {
      val html =
        <div id="notifications" class="alert alert-danger text-center col-lg-12 col-xs-12 col-sm-12" role="alert">
          <ul class="text-danger">{notifications.map( n => <li>{n}</li>) }</ul>
        </div>
      html
    }
  }

  ///////////// fields for category settings ///////////////////

  private[this] val piReasons = {
    import com.normation.rudder.web.services.ReasonBehavior._
    userPropertyService.reasonsFieldBehavior match {
      case Disabled => None
      case Mandatory => Some(buildReasonField(true, "subContainerReasonField"))
      case Optionnal => Some(buildReasonField(false, "subContainerReasonField"))
    }
  }

  def buildReasonField(mandatory:Boolean, containerClass:String = "twoCol") = {
    new WBTextAreaField("Change audit message", "") {
      override def setFilter = notNull _ :: trim _ :: Nil
      override def inputField = super.inputField  %
        ("style" -> "height:5em;") % ("placeholder" -> {userPropertyService.reasonsFieldExplanation})
      override def errorClassName = "col-lg-12 errors-container"
      override def validations() = {
        if(mandatory){
          valMinLen(5, "The reason must have at least 5 characters.") _ :: Nil
        } else {
          Nil
        }
      }
    }
  }

  private[this] val piName = {
      new WBTextField("Name",
        nodeGroup.map(x => "Copy of <%s>".format(x.name)).getOrElse("")) {
      override def setFilter = notNull _ :: trim _ :: Nil
      override def errorClassName = "col-lg-12 errors-container"
      override def inputField = super.inputField %("onkeydown" , "return processKey(event , 'createCOGSaveButton')") % ("tabindex","1")
      override def validations =
        valMinLen(3, "The name must have at least 3 characters") _ :: Nil
    }
  }

  private[this] val piDescription = new WBTextAreaField("Description",
      nodeGroup.map(x => x.description).getOrElse("") ) {
    override def setFilter = notNull _ :: trim _ :: Nil
    override def inputField = super.inputField  % ("style" -> "height:5em") % ("tabindex","3")
    override def errorClassName = "col-lg-12 errors-container"
    override def validations =  Nil
  }

  private[this] val piStatic =
    new WBRadioField("Group type", Seq("static", "dynamic"),
        ((nodeGroup.map(x => x.isDynamic).getOrElse(false)) ? "dynamic" | "static"), {
    case "static" =>
      <span title="The list of member nodes is defined at creation and will not change automatically.">Static</span>
    case "dynamic" =>
      <span title="Nodes will be automatically added and removed so that the list of members always matches this group's search criteria.">Dynamic</span>
  }, Some(4)) {
    override def className = "col-lg-12 col-sm-12 col-xs-12"
    override def errorClassName = "col-lg-12 errors-container"
    override def setFilter = notNull _ :: trim _ :: Nil
    override def inputField = super.inputField %("onkeydown" , "return processKey(event , 'createCOGSaveButton')")
  }

  private[this] val piContainer = new WBSelectField("Parent category",
      (categories.getOrElse(Seq()).map(x => (x.id.value -> x.name))),
      parentCategoryId) {
    override def inputField = super.inputField %("onkeydown" , "return processKey(event , 'createCOGSaveButton')") % ("tabindex","2")
    override def className = "col-lg-12 col-sm-12 col-xs-12  form-control"
    override def validations =
      valMinLen(1, "Please select a category") _ :: Nil
  }

  private[this] def initJs : JsCmd = {
    JsShowId("createGroupHiddable") &
    {
      if (groupGenerator != None) {
        JsHideId("itemCreation")
      } else {
        Noop
      }
    }
  }

  private[this] val formTracker = {
    new FormTracker(piName, piDescription, piContainer, piStatic)
  }

  private[this] def updateFormClientSide() : JsCmd = {
    SetHtml("createCloneGroupContainer", popupContent())
  }
}
