// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.workspaceModel.ide.impl.legacyBridge.project

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diagnostic.trace
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.util.indexing.EntityIndexingServiceEx
import com.intellij.workspaceModel.ide.impl.legacyBridge.module.ModuleRootListenerBridge
import com.intellij.workspaceModel.ide.impl.legacyBridge.watcher.VirtualFileUrlWatcher
import com.intellij.workspaceModel.storage.EntityChange
import com.intellij.workspaceModel.storage.VersionedStorageChange

internal object ModuleRootListenerBridgeImpl : ModuleRootListenerBridge {

  private val LOG = logger<ModuleRootListenerBridgeImpl>()

  override fun fireBeforeRootsChanged(project: Project, event: VersionedStorageChange) {
    LOG.trace { "fireBeforeRootsChanged call" }
    ApplicationManager.getApplication().assertWriteAccessAllowed()
    if (project.isDisposed) {
      LOG.trace { "Project is disposed" }
      return
    }

    if (VirtualFileUrlWatcher.getInstance(project).isInsideFilePointersUpdate) {
      LOG.trace { "isInsideFilePointersUpdate is true" }
      //the old implementation doesn't fire rootsChanged event when roots are moved or renamed, let's keep this behavior for now
      return
    }
    
    val projectRootManager = ProjectRootManager.getInstance(project)
    if (projectRootManager !is ProjectRootManagerBridge) {
      LOG.trace { "projectRootManager is not a ProjectRootManagerBridge" }
      return
    }
    val performUpdate = shouldFireRootsChanged(event, project)
    if (performUpdate) {
      LOG.trace { "Perform update" }
      projectRootManager.rootsChanged.beforeRootsChanged()
    }
  }

  override fun fireRootsChanged(project: Project, event: VersionedStorageChange) {
    LOG.trace { "fireRootsChanged call" }
    ApplicationManager.getApplication().assertWriteAccessAllowed()
    if (project.isDisposed) {
      LOG.trace { "Project is disposed" }
      return
    }

    if (VirtualFileUrlWatcher.getInstance(project).isInsideFilePointersUpdate) {
      LOG.trace { "isInsideFilePointersUpdate is true" }
      //the old implementation doesn't fire rootsChanged event when roots are moved or renamed, let's keep this behavior for now
      return
    }

    val projectRootManager = ProjectRootManager.getInstance(project)
    if (projectRootManager !is ProjectRootManagerBridge) {
      LOG.trace { "projectRootManager is not a ProjectRootManagerBridge" }
      return
    }
    val performUpdate = shouldFireRootsChanged(event, project)
    if (performUpdate) {
      LOG.trace { "Perform update" }
      val rootsChangeInfo = EntityIndexingServiceEx.getInstanceEx().createWorkspaceChangedEventInfo(event.getAllChanges().toList())
      projectRootManager.rootsChanged.rootsChanged(rootsChangeInfo)
    }
  }

  private fun shouldFireRootsChanged(events: VersionedStorageChange, project: Project): Boolean {
    val indexingService = EntityIndexingServiceEx.getInstanceEx()
    return events.getAllChanges().any {
      val entity = when (it) {
        is EntityChange.Added -> it.entity
        is EntityChange.Removed -> it.entity
        is EntityChange.Replaced -> it.newEntity
      }
      indexingService.shouldCauseRescan(entity, project)
    }
  }
}