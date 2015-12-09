/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

package com.normation.rudder.web.comet

import scala.xml._
import net.liftweb.common._
import net.liftweb.util._
import Helpers._
import net.liftweb.http._
import js._
import JE._
import JsCmds._
import com.normation.rudder.batch._
import org.joda.time.Duration
import org.joda.time.format.PeriodFormatterBuilder
import com.normation.rudder.web.components.DateFormaterService
import com.normation.rudder.web.model.CurrentUser
import com.normation.rudder.services.eventlog.EventLogDeploymentService
import com.normation.eventlog.EventLog
import com.normation.rudder.domain.eventlog.ModificationWatchList
import com.normation.eventlog.UnspecializedEventLog
import com.normation.rudder.domain.eventlog.RudderEventActor
import org.joda.time.DateTime
import net.liftweb.common.EmptyBox
import com.normation.rudder.web.services.EventListDisplayer
import com.normation.eventlog.EventLogDetails
import com.normation.utils.StringUuidGenerator
import com.normation.eventlog.ModificationId
import bootstrap.liftweb.RudderConfig

class AsyncDeployment extends CometActor with CometListener with Loggable {

  private[this] val periodFormatter = new PeriodFormatterBuilder().
    appendDays().
    appendSuffix(" day ", " days ").
    appendSeparator(", ").
    appendMinutes().
    appendSuffix(" min ", " min ").
    appendSeparator(" ").
    appendSeconds().
    appendSuffix("s", "s")
    .toFormatter()

  private[this] def formatPeriod(duration:Duration) : String = {
    if(duration.getMillis < 1000) "less than 1s"
    else periodFormatter.print(duration.toPeriod)
  }

  private[this] val asyncDeploymentAgent      = RudderConfig.asyncDeploymentAgent
  private[this] val eventLogDeploymentService = RudderConfig.eventLogDeploymentService
  private[this] val eventList                 = RudderConfig.eventListDisplayer
  private[this] val uuidGen                   = RudderConfig.stringUuidGenerator


  //current states of the deployment
  private[this] var deploymentStatus = DeploymentStatus(NoStatus, IdleDeployer)

  // the last deployment data
  private[this] var lastSuccessfulDeployement : Box[EventLog] = eventLogDeploymentService.getLastSuccessfulDeployement()
  private[this] var lastEventSinceDeployment : Box[Seq[EventLog]] = Empty

  override def registerWith = asyncDeploymentAgent

  override def lowPriority = {
    case d:DeploymentStatus => deploymentStatus = d ; reRender()
  }

  override def render = {
    new RenderOut((
      ClearClearable &
      "#deploymentLastStatus *" #> lastStatus &
      "#deploymentProcessing *" #> currentStatus
    )(layout) , JsRaw("""$("button.deploymentButton").button(); """))
  }


  val deployementErrorMessage = """(.*)!errormessage!(.*)""".r

  private[this] def lastStatus : NodeSeq = {

    deploymentStatus.current match {
      case NoStatus => <span>Policy update status unavailable</span>
      case SuccessStatus(id,start,end,configurationNodes) =>
        <span class="deploymentSuccess">
          <img src="/images/icOK.png" alt="Error" height="16" width="16" class="iconscala" />
          Success: Policies updated at {DateFormaterService.getFormatedDate(start)} (took {formatPeriod(new Duration(start,end))})
        </span>
      case ErrorStatus(id,start,end,failure) =>
        {<span class="error deploymentError"><img src="/images/icfail.png" alt="Error" height="16" width="16" class="iconscala" />
          Error during policy update at {DateFormaterService.getFormatedDate(start)} <br/>(took {formatPeriod(new Duration(start,end))} -
          <span class="errorscala" id="errorDetailsLink" onClick={
            """$('#errorDetailsDialog').modal({ minHeight:140, minWidth: 300 });
               $('#simplemodal-container').css('height', 'auto');
               correctButtons();
               return false;"""}>details</span>)
        </span>} ++ {
          ("#errorDetailsMessage" #> { failure.messageChain match {
            case  deployementErrorMessage(chain, error) =>
              <span>{chain.split("<-").map(x => Text("⇨ " + x) ++ {<br/>})}</span>
              <br/>
              <div class="curspoint listopen" onClick="$('#deploymentErrorMsg').toggle();$('#simplemodal-container').css('width', '80%');$('#simplemodal-container').resize();$(this).toggleClass('listopen');$(this).toggleClass('listclose');"><b>Show technical details</b></div>
              <br/>
              <fieldset id="deploymentErrorMsg" style="display:none;"><legend><b>Technical details</b></legend>
                <span>{error.split("<-").map(x => Text("⇨ " + x) ++ {<br/>})}<br/></span>
              </fieldset>
            case _ => <span>{failure.messageChain.split("<-").map(x => Text("⇨ " + x) ++ {<br/>})}</span>
          } }).apply(errorPopup)
        }
    }
  }

  private[this] def currentStatus : NodeSeq = {
    deploymentStatus.processing match {
      case IdleDeployer =>
        <lift:authz role="deployment_write"> {
          SHtml.ajaxButton("Update", { () =>
            asyncDeploymentAgent ! ManualStartDeployment(ModificationId(uuidGen.newUuid), CurrentUser.getActor, "User requested a manual policy update") //TODO: let the user fill the cause
            Noop
          }, ( "class" , "deploymentButton")) }
        </lift:authz>
      case Processing(id, start) =>
        <span>
          <img src="/images/deploying.gif" alt="Updating..." height="16" width="16" class="iconscala" />
          Updating policies (started at {DateFormaterService.getFormatedDate(start)})
        </span>
      case ProcessingAndPendingAuto(asked, Processing(id, start), actor, logId) =>
        <span>
          <img src="/images/deploying.gif" alt="Updating..." height="16" width="16" class="iconscala" />
          Updating policies (started at {DateFormaterService.getFormatedDate(start)}). Another update is pending since {DateFormaterService.getFormatedDate(asked)}
        </span>
      case ProcessingAndPendingManual(asked, Processing(id, start), actor, logId, cause) =>
        <span>
          <img src="/images/deploying.gif" alt="Updating..." height="16" width="16" class="iconscala" />
          Updating policies (started at {DateFormaterService.getFormatedDate(start)}). Another update is pending since {DateFormaterService.getFormatedDate(asked)}
        </span>
    }
  }

  private[this] def layout = {
    <div id="deploymentStatus">
      <div style="font-size: 14px; font-weight: bold; margin-bottom:7px;">Rudder status</div>
      <lift:ignore>
        Here come the status of the last finised policy update.
        Status can be: no previous policy update, correctly updated, warning, error.
      </lift:ignore>
      <div id="deploymentLastStatus">
        [Here comes the status of the last finished deployement]
      </div>

      <lift:ignore>
        Here comes an indication of the current deployement.
        May be : not updating (a button is shown to start a policy update), updating (give an idea of the time remaining ?), updating + one pending
      </lift:ignore>
      <div id="deploymentProcessing">
        [Here comes the current deployment processing]
      </div>
    </div>
  }

  private[this] def errorPopup = {
    <div id="errorDetailsDialog" class="nodisplay">
      <div class="simplemodal-title">
        <h1>Error</h1>
        <hr/>
      </div>
      <div class="simplemodal-content">
       <br />
        <div>
          <img src="/images/icfail.png" alt="Error" height="24" width="24" class="erroricon" />
          <h2>Policy update process was stopped due to an error:</h2>
        </div>
        <hr class="spacer" />
        <br />
        <p id="errorDetailsMessage">[Here come the error message]</p>
        <hr class="spacer" />
      </div>
      <div class="simplemodal-bottom">
        <hr/>
        <div class="popupButton">
          <span>
            <button class="simplemodal-close" onClick="return false;">
              Close
            </button>
          </span>
        </div>
      </div>
    </div>
  }

}
