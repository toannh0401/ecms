/**
 *  Copyright (C) 2003-2010 eXo Platform SAS.
 *
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Affero General Public License
 *  as published by the Free Software Foundation; either version 3
 *  of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, see<http://www.gnu.org/licenses/>.
 */

package org.exoplatform.ecms.xcmis.sp.jcr.exo;

import org.exoplatform.container.component.ComponentPlugin;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.container.xml.ValueParam;
import org.exoplatform.services.cms.drives.DriveData;
import org.exoplatform.services.cms.drives.ManageDriveService;
import org.exoplatform.services.document.DocumentReaderService;
import org.exoplatform.services.jcr.RepositoryService;
import org.exoplatform.services.jcr.access.SystemIdentity;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;
import org.exoplatform.services.security.MembershipEntry;
import org.xcmis.search.config.IndexConfiguration;
import org.xcmis.spi.CmisRuntimeException;
import org.xcmis.spi.Connection;
import org.xcmis.spi.PermissionService;
import org.xcmis.spi.StorageProvider;
import org.xcmis.spi.deploy.ExoContainerCmisRegistry;
import org.xcmis.spi.model.RepositoryShortInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * @author <a href="mailto:andrew00x@gmail.com">Andrey Parfonov</a>
 * @version $Id$
 */
public class DriveCmisRegistry extends ExoContainerCmisRegistry
{

   private static final Log LOG = ExoLogger.getLogger(DriveCmisRegistry.class);

   Map<String, TypeMapping> nodeTypeMapping;

   private RepositoryService repositoryService;

   private DocumentReaderService documentReaderService;

   private ManageDriveService driveService;

   private String repository = "repository";

   private PermissionService permissionService;

   public DriveCmisRegistry(RepositoryService repositoryService, DocumentReaderService documentReaderService,
      ManageDriveService driveService, InitParams initParams)
   {
      super(initParams);
      this.repositoryService = repositoryService;
      this.documentReaderService = documentReaderService;
      this.permissionService = new PermissionService();
      this.driveService = driveService;
      this.nodeTypeMapping = new HashMap<String, TypeMapping>();
   }

   public DriveCmisRegistry(RepositoryService repositoryService, ManageDriveService driveService, InitParams initParams)
   {
      this(repositoryService, null, driveService, initParams);
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public Set<RepositoryShortInfo> getStorageInfos()
   {
      List<String> memberships = null;
      String userId = null;
      ConversationState state = ConversationState.getCurrent();
      if (state != null)
      {
         Identity identity = state.getIdentity();
         if (identity != null)
         {
            userId = identity.getUserId();
            memberships = new ArrayList<String>(identity.getMemberships().size());
            for (MembershipEntry membership : identity.getMemberships())
            {
               memberships.add(membership.toString());
            }
         }
      }
      if (userId == null)
      {
         userId = SystemIdentity.ANONIM;
      }
      if (memberships == null)
      {
         memberships = Collections.emptyList();
      }

      SortedSet<RepositoryShortInfo> repositories = new TreeSet<RepositoryShortInfo>();
      List<DriveData> availableDrives = null;
      try
      {
         availableDrives = driveService.getDriveByUserRoles(repository, userId, memberships);
      }
      catch (Exception e)
      {
         LOG.error(e.getMessage(), e);
      }

      if (availableDrives != null)
      {
         for (DriveData drive : availableDrives)
         {
            String driveName = drive.getName();
            StorageProvider provider = storageProviders.get(driveName);
            if (provider == null)
            {
               // TODO personal user drives here
            }
            else
            {
               Connection connection = null;
               try
               {
                  String storageID = provider.getStorageID();
                  RepositoryShortInfo info = new RepositoryShortInfo(storageID, storageID);
                  connection = provider.getConnection();
                  info.setRootFolderId(connection.getStorage().getRepositoryInfo().getRootFolderId());
                  repositories.add(info);
               }
               finally
               {
                  if (connection != null)
                  {
                     connection.close();
                  }
               }
            }
         }
      }
      return Collections.unmodifiableSortedSet(repositories);
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public void start()
   {
      String rootIndexDir = getValueParameter("rootIndexDir", null);
      String repository1 = getValueParameter("repository", repository);
      boolean persistRenditions = Boolean.parseBoolean(getValueParameter("exo.cmis.renditions.persistent", "false"));
      List<DriveData> allDrives = null;
      try
      {
         allDrives = driveService.getAllDrives(repository1);
      }
      catch (Exception e)
      {
         throw new CmisRuntimeException("Unable get list of drives. " + e.getMessage(), e);
      }
      for (DriveData drive : allDrives)
      {
         if (isPrivateDrive(drive))
         {
            // Do nothing with private drive since we don't know user's name
            // and can't determine root path.
            continue;
         }
         String driveName = drive.getName();
         String driveRootPath = drive.getHomePath();
         String driveWorkspace = drive.getWorkspace();
         String indexDir =
            rootIndexDir != null ? (rootIndexDir.charAt(rootIndexDir.length() - 1) == File.separatorChar ? rootIndexDir
               + driveName : rootIndexDir + File.separatorChar + driveName) : null;
         IndexConfiguration indexConfiguration = null;
         if (indexDir != null)
         {
            indexConfiguration = new IndexConfiguration();
            indexConfiguration.setIndexDir(indexDir);
         }
         Map<String, Object> properties = new HashMap<String, Object>();
         properties.put("exo.cmis.renditions.persistent", persistRenditions);
         StorageConfiguration configuration =
            new StorageConfiguration(driveName, repository1, driveWorkspace, driveRootPath, indexConfiguration,
               properties, null);
         StorageProviderImpl sp =
            new StorageProviderImpl(repositoryService, documentReaderService, permissionService, this, configuration);

         sp.addNodeTypeMapping(nodeTypeMapping);
         try
         {
            sp.init();
            addStorage(sp);
         }
         catch (Exception e)
         {
            LOG.error("Initializing of storage provider " + driveName + " failed. ", e);
         }
      }

      super.start();
   }

   protected boolean isPrivateDrive(DriveData drive)
   {
      // TODO is there something better in WCM API ?
      return drive.getHomePath().contains("${userId}");
   }

   public void addPlugin(ComponentPlugin plugin)
   {
      if (plugin instanceof TypeMappingPlugin)
      {
         nodeTypeMapping.putAll(((TypeMappingPlugin)plugin).getTypeMapping());
      }
   }

   private String getValueParameter(String name, String defaultValue)
   {
      String value = null;
      if (initParams != null)
      {
         ValueParam valueParam = initParams.getValueParam(name);
         if (valueParam != null)
         {
            value = valueParam.getValue();
         }
      }
      return value != null ? value : defaultValue;
   }
}
