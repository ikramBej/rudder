/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.repository.xml

import org.apache.commons.io.FileUtils

import com.normation.rudder.repository._
import com.normation.utils.Control._

import net.liftweb.common._
import net.liftweb.util.Helpers.tryo


class ItemArchiveManagerImpl(
    configurationRuleRepository          : ConfigurationRuleRepository
  , uptRepository                        : UserPolicyTemplateRepository
  , groupRepository                      : NodeGroupRepository
  , gitConfigurationRuleArchiver         : GitConfigurationRuleArchiver
  , gitUserPolicyTemplateCategoryArchiver: GitUserPolicyTemplateCategoryArchiver
  , gitUserPolicyTemplateArchiver        : GitUserPolicyTemplateArchiver
  , gitNodeGroupCategoryArchiver         : GitNodeGroupCategoryArchiver
  , gitNodeGroupArchiver                 : GitNodeGroupArchiver
  , parseConfigurationRules              : ParseConfigurationRules
  , parsePolicyLibrary                   : ParsePolicyLibrary
  , importPolicyLibrary                  : ImportPolicyLibrary
  , parseGroupLibrary                    : ParseGroupLibrary
  , importGroupLibrary                   : ImportGroupLibrary
) extends ItemArchiveManager with Loggable {
  
  ///// implementation /////
  
  def exportAll(includeSystem:Boolean = false): Box[ArchiveId] = { 
    for {
      saveCrs     <- exportConfigurationRules(includeSystem)
      saveUserLib <- exportPolicyLibrary(includeSystem)
      saveGroups  <- exportGroupLibrary(includeSystem)
    } yield {
      saveUserLib
    }
  }
  
  def importAll(includeSystem:Boolean = false) : Box[Unit] = {
    for {
      configurationRules <- importConfigurationRules(includeSystem)
      userLib            <- importPolicyLibrary(includeSystem)
      groupLIb           <- importGroupLibrary(includeSystem)
    } yield {
      configurationRules
    }
  }
  
  def exportConfigurationRules(includeSystem:Boolean = false): Box[ArchiveId] = { 
    for {
      crs         <- configurationRuleRepository.getAll(false)
      cleanedRoot <- tryo { FileUtils.cleanDirectory(gitConfigurationRuleArchiver.getRootDirectory) }
      saved       <- sequence(crs) { cr => 
                       gitConfigurationRuleArchiver.archiveConfigurationRule(cr,false)
                     }
      commitId    <- gitConfigurationRuleArchiver.commitConfigurationRules
    } yield {
      ArchiveId(commitId)
    }
  }
  
  def exportPolicyLibrary(includeSystem:Boolean = false): Box[ArchiveId] = { 
    for { 
      catWithUPT   <- uptRepository.getUPTbyCategory(includeSystem = true)
      //remove systems things if asked (both system categories and system upts in non-system categories)
      okCatWithUPT =  if(includeSystem) catWithUPT
                      else catWithUPT.collect { 
                          //always include root category, even if it's a system one
                          case (categories, CategoryAndUPT(cat, upts)) if(cat.isSystem == false || categories.size <= 1) => 
                            (categories, CategoryAndUPT(cat, upts.filter( _.isSystem == false )))
                      }
      cleanedRoot <- tryo { FileUtils.cleanDirectory(gitUserPolicyTemplateCategoryArchiver.getRootDirectory) }
      savedItems  <- sequence(okCatWithUPT.toSeq) { case (categories, CategoryAndUPT(cat, upts)) => 
                       for {
                         //categories.tail is OK, as no category can have an empty path (id)
                         savedCat  <- gitUserPolicyTemplateCategoryArchiver.archiveUserPolicyTemplateCategory(cat,categories.reverse.tail, gitCommit = false)
                         savedUpts <- sequence(upts.toSeq) { upt =>
                                        gitUserPolicyTemplateArchiver.archiveUserPolicyTemplate(upt,categories.reverse, gitCommit = false)
                                      }
                       } yield {
                         "OK"
                       }
                     }
      commitId    <- gitUserPolicyTemplateCategoryArchiver.commitUserPolicyLibrary
    } yield {
      ArchiveId(commitId)
    }
  }
  
  def exportGroupLibrary(includeSystem:Boolean = false): Box[ArchiveId] = { 
    for { 
      catWithGroups   <- groupRepository.getGroupsByCategory(includeSystem = true)
      //remove systems things if asked (both system categories and system groups in non-system categories)
      okCatWithGroup  =  if(includeSystem) catWithGroups
                         else catWithGroups.collect { 
                            //always include root category, even if it's a system one
                            case (categories, CategoryAndNodeGroup(cat, groups)) if(cat.isSystem == false || categories.size <= 1) => 
                              (categories, CategoryAndNodeGroup(cat, groups.filter( _.isSystem == false )))
                         }
      cleanedRoot     <- tryo { FileUtils.cleanDirectory(gitNodeGroupCategoryArchiver.getRootDirectory) }
      savedItems      <- sequence(okCatWithGroup.toSeq) { case (categories, CategoryAndNodeGroup(cat, groups)) => 
                           for {
                             //categories.tail is OK, as no category can have an empty path (id)
                             savedCat    <- gitNodeGroupCategoryArchiver.archiveNodeGroupCategory(cat,categories.reverse.tail, gitCommit = false)
                             savedgroups <- sequence(groups.toSeq) { group =>
                                              gitNodeGroupArchiver.archiveNodeGroup(group,categories.reverse, gitCommit = false)
                                            }
                           } yield {
                             "OK"
                           }
                         }
      commitId        <- gitNodeGroupCategoryArchiver.commitGroupLibrary
    } yield {
      ArchiveId(commitId)
    }
  }
  
  def importConfigurationRules(includeSystem:Boolean = false) : Box[Unit] = {
    for {
      parsed   <- parseConfigurationRules.getLastArchive
      imported <- configurationRuleRepository.swapConfigurationRules(parsed)
    } yield {
      //try to clean
      configurationRuleRepository.deleteSavedCr(imported) match {
        case eb:EmptyBox =>
          val e = eb ?~! ("Error when trying to delete saved archive of old cr: " + imported)
          logger.error(e)
        case _ => //ok
      }
      parsed
    }
  }
  
  def importPolicyLibrary(includeSystem:Boolean) : Box[Unit] = {
      for {
        parsed   <- parsePolicyLibrary.getLastArchive
        imported <- importPolicyLibrary.swapUserPolicyLibrary(parsed, includeSystem)
      } yield {
        imported
      }
  }
  
  def importGroupLibrary(includeSystem:Boolean) : Box[Unit] = {
      for {
        parsed   <- parseGroupLibrary.getLastArchive
        imported <- importGroupLibrary.swapGroupLibrary(parsed, includeSystem)
      } yield {
        imported
      }
  }
}