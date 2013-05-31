package org.syncany.operations;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.syncany.config.Config;
import org.syncany.connection.plugins.RemoteFile;
import org.syncany.connection.plugins.StorageException;
import org.syncany.connection.plugins.TransferManager;
import org.syncany.database.Database;
import org.syncany.database.DatabaseDAO;
import org.syncany.database.DatabaseVersion;
import org.syncany.database.DatabaseVersionHeader;
import org.syncany.database.VectorClock;
import org.syncany.database.VectorClock.VectorClockComparison;

public class SyncDownOperation extends Operation {
	private static final Logger logger = Logger.getLogger(SyncDownOperation.class.getSimpleName());
	
	public SyncDownOperation(Config profile) {
		super(profile);
	}	
	
	public void execute() throws Exception {
		logger.log(Level.INFO, "Sync down  ...");
		
		File localDatabaseFile = new File(profile.getAppDatabaseDir()+"/local.db");		
		Database db = loadLocalDatabase(localDatabaseFile);
		
		// 0. Create TM
		TransferManager transferManager = profile.getConnection().createTransferManager();

		// 1. Check which remote databases to download based on the last local vector clock		
		List<RemoteFile> unknownRemoteDatabases = listUnknownRemoteDatabases(db, transferManager);
		
		// 2. Download the remote databases to the local cache folder
		List<File> unknownRemoteDatabasesInCache = downloadUnknownRemoteDatabases(transferManager, unknownRemoteDatabases);
		
		// 3. Read version headers (vector clocks)
		List<DatabaseVersionHeader> unknownDatabaseVersionHeaders = readUnknownDatabaseVersionHeaders(unknownRemoteDatabasesInCache);
		
		detectUpdates(unknownDatabaseVersionHeaders);
		// 3. read the remote databases
		// 4. compare the remote databases based on the file histories contained in them and figure out the winning file histories
		//detectUpdates(db, unknownRemoteDatabasesInCache);
		
		// 5. figure out which 
		// 2. xxx
		//
		//db.getLastDatabaseVersion().getVectorClock();
		
		throw new Exception("Not yet fully implemented.");
		//return false;
	}	

	private void detectUpdates(List<DatabaseVersionHeader> unknownDatabaseVersionHeaders) {
		// 0. Create ascending order, 
		List<DatabaseVersionHeader> sortedUnknownDatabaseVersionHeaders = new ArrayList<DatabaseVersionHeader>(unknownDatabaseVersionHeaders);		
		Collections.sort(sortedUnknownDatabaseVersionHeaders, new DatabaseVersionHeaderComparator());
		
		// 1. Get all conflicts
		List<DatabaseVersionHeaderPair> conflicts = new ArrayList<DatabaseVersionHeaderPair>();
		Set<DatabaseVersionHeader> conflictHeaders = new HashSet<DatabaseVersionHeader>();
		
		for (int i=0; i<unknownDatabaseVersionHeaders.size(); i++) { // compare all clocks to each other
			for (int j=i+1; j<unknownDatabaseVersionHeaders.size(); j++) {
				if (j != i) {
					DatabaseVersionHeader header1 = unknownDatabaseVersionHeaders.get(i);
					DatabaseVersionHeader header2 = unknownDatabaseVersionHeaders.get(j);
					
					VectorClockComparison vectorClockComparison = VectorClock.compare(header1.getVectorClock(), header2.getVectorClock());
					
					if (vectorClockComparison == VectorClockComparison.SIMULTANEOUS) {
						conflictHeaders.add(header1);
						conflictHeaders.add(header2);						
						conflicts.add(new DatabaseVersionHeaderPair(header1, header2));
					}
				}
			}
		}		
		
		// 2. Determine the first conflict-free database version headers (1..n)
		List<DatabaseVersionHeader> firstUnconflictingUnknownDatabaseVersionHeaders = new ArrayList<DatabaseVersionHeader>();
		
		DatabaseVersionHeader thisHeader = null;
		DatabaseVersionHeader lastHeader = null;
		
		for (int i=0; i < sortedUnknownDatabaseVersionHeaders.size(); i++) {
			thisHeader = sortedUnknownDatabaseVersionHeaders.get(i);
			
			if (conflictHeaders.contains(thisHeader)) {	// stop when first conflict found			 
				break; // TODO (A, see below!)
			}
			
			if (lastHeader != null && lastHeader.equals(thisHeader)) { // ignore duplicates
				continue;
			}
			
			firstUnconflictingUnknownDatabaseVersionHeaders.add(thisHeader);			
			lastHeader = thisHeader;
		}
		/*
		 * T    A                 B                C                     Remote Storage
		 *  
		 * ////////////////////////////////////////////////////////////////////////////////////////
		 *      
		 * 01                                      (  ,  ,C1) ---------> db-c-1
		 * 02                                      (  ,  ,C2)         
		 * 03                                      (  ,  ,C3) ---------> db-c-3
		 * 04                     (  ,  ,C1) <
		 * 04                     (  ,  ,C2) <
		 * 04                     (  ,  ,C3) <-------------------------- db-c-1, db-c-3
		 * 05                                      (  ,  ,C4) ---------> db-c-4
		 * 06   (  ,  ,C1) <
		 * 06   (  ,  ,C2) <
		 * 06   (  ,  ,C3) <
		 * 06   (  ,  ,C4) <-------------------------------------------- db-c-1, db-c-3, db-c-4
		 * 07                     (  ,B1,C3) --------------------------> db-b-1
		 * 08   (A1,  ,C4) 
		 * 09   (A2,  ,C4) 
		 * 10   (A3,  ,C4) --------------------------------------------> db-a-3
		 *      
		 *                        (CONFLICT 1) <------------------------ db-c-4, db-a-3
		 *                        (  ,  ,C4) wins
		 *                        (  ,B2,C4) after merge
		 *                        To be uploaded
		 *                        
		 *      (CONFLICT 2) <------------------------------------------ db-b-1
		 *      (  ,B1,C3) wins
		 *      (A4,B1,C3) after merge
		 *      To be uploaded
		 *                                         (CONFLICT 3) <------- db-a-3, db-b-1
		 *                                         (  ,  ,C4) wins
		 *                                         Nothing to merge
		 *                                         Nothing to upload
		 *                                         
		 * 11                     (  ,B2,C4) --------------------------> db-b-2
		 * 12   (A4,B1,C3) --------------------------------------------> db-a-4
		 * 
		 *                        (CONFLICT 4) <------------------------ db-a-4
		 *                        (  ,B2,C4) wins
		 *                        Nothing to merge
		 *                        Nothing to upload
		 *                        
		 *      (CONFLICT 5) <------------------------------------------ db-b-2
		 *      (  ,B2,C4) wins
		 *      (A5,B2,C4) after merge
		 *      To be uploaded
		 *      
		 *                                         (CONFLICT 6) <------- db-a-4, db-b-2
		 *                                         (  ,B2,C4) wins
		 *                                         Apply changes locally
		 *                                         Nothing to upload
		 *                                         
		 * 13  (A5,B2,C4) ---------------------------------------------> db-a-5  
		 * 
		 *                                         (CONFLICT 7) <------- db-a-5
		 *                                         (A5,B2,C4) wins
		 *                                         Apply changes locally
		 *                                         Nothing to upload
		 *                                         
		 *                        (CONFLICT 8) <------------------------ db-a-4
		 *                        (A5,B2,C4) wins
		 *                        Apply changes locally
		 *                        Nothing to upload
		 *                                    
		 * 
		 * ////////////////////////////////////////////////////////////////////////////////////////     
		 *  
		 * CONFLICT 1 at B: 
		 *  - Local:     (  ,B1,C3)/T=07
		 *  - db-c-4:    (  ,  ,C4)/T=05
		 *  - db-a-3:    (A1,  ,C4)/T=08
		 *               (A2,  ,C4)/T=09
		 *               (A3,  ,C4)/T=10
		 *               
		 *  - Sorted:    (  ,  ,C4) < (A1,  ,C4) < (A2,  ,C4) < (A3,  ,C4)
		 *  - Conflicts: (  ,B1,C3)/T=07 || (  ,  ,C4)/T=05 --> (  ,  ,C4)/T=05
		 *               (  ,B1,C3)/T=07 || (A1,  ,C4)/T=08 --> (  ,B1,C3)/T=07
		 *               (  ,B1,C3)/T=07 || (A2,  ,C4)/T=09 --> (  ,B1,C3)/T=07 
		 *               (  ,B1,C3)/T=07 || (A3,  ,C4)/T=10 --> (  ,B1,C3)/T=07
		 *               .
		 *               New: 
		 *               (  ,  ,C4)/T=05 || (  ,B1,C3)/T=07 --> (  ,  ,C4)/T=05 
		 *  - Result:
		 *     Winner: (  ,  ,C4)/T=05 
		 *     --> Conflicts with local version
		 *     --> Local must merge local version (  ,B1,C3) in (  ,  ,C4)
		 *     --> Local result is then (  ,B2,C4)
		 * 
		 * CONFLICT 2 at A: 
		 *  - Local:     (A3,  ,C4)/T=10
		 *  - db-b-1:    (  ,B1,C3)/T=07
		 *               
		 *  - Sorted:    (empty)
		 *  - Conflicts: (A3,  ,C4)/T=10 || (  ,B1,C3)/T=07 --> (  ,B1,C3)/T=07
		 *  - Result:
		 *     Winner: (  ,B1,C3)/T=07 
		 *     --> Conflicts with local version
		 *     --> Local must merge local version (A3,  ,C4) in (  ,B1,C3)
		 *     --> Local result is then (A4,B1,C3)
		 *
		 *     
		 *     TODO   WHAT ABOUT C4??? 
		 *     TODO   SHOULD WE THROW OLDER LOCAL VERSIONS IN THE MIX TOO?
		 *     TODO   SHOULDN'T WE COMPARE (  ,B1,C3) with the older local version (  ,  ,C4)  (= first conflicting local version!)
		 *     TODO   THEN, the branch of (  ,  ,C4) would win and the second conflict round would not be necessary!!!!!!!!!!!!!!!!!!!!!!!
		 *              ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^  
		 * 
		 * 
		 * 
		 * 
		 * 
		 * CONFLICT 3 at C: 
		 *  - Local:     (  ,  ,C4)/T=05
		 *  - db-a-3:    (A1,  ,C4)/T=08
		 *               (A2,  ,C4)/T=09
		 *               (A3,  ,C4)/T=10
		 *  - db-b-1:    (  ,B1,C3)/T=07
		 *               
		 *  - Sorted:    (  ,  ,C4) < (A1,  ,C4) < (A2,  ,C4) < (A3,  ,C4)
		 *  - Conflicts: (  ,B1,C3)/T=07 || (  ,  ,C4)/T=05 --> (  ,  ,C4)/T=05
		 *               (  ,B1,C3)/T=07 || (A1,  ,C4)/T=08 --> (  ,B1,C3)/T=07
		 *               (  ,B1,C3)/T=07 || (A2,  ,C4)/T=09 --> (  ,B1,C3)/T=07 
		 *               (  ,B1,C3)/T=07 || (A3,  ,C4)/T=10 --> (  ,B1,C3)/T=07
		 *               .
		 *               New: 
		 *               (  ,  ,C4)/T=05 || (  ,B1,C3)/T=07 --> (  ,  ,C4)/T=05 
		 *  - Result:
		 *     Winner: (  ,  ,C4)/T=05 
		 *     --> Same as local version
		 *     --> Nothing to do!
		 * 
		 */
		// TODO
		// Now  firstUnconflictingUnknownDatabaseVersionHeaders should contain 
		// 1.n database versions that do not conflict and could be safely applied locally
		
		// To determine the rest, conflicts must be resolved.
		// Problem: determining the winner is not enough, because the whole branch of DBVs must be determined
		// Possible solution: 
		/*
		   readUnknownDatabaseVersionHeaders should return a branch of DBVs for each client (so a Map<Client, Map<LocalVersion, DBV>>>)
		   This map mu
		   
		   ARRG!
		 */
		
		/*
		 
		 A  B  C
		 1  1  1
		 2  2  2
		 x  y  x   // Conflict, A/C wins, choose A
		 |  i  i   // Ignore B and C databases 
		 |  i
		 |
		  
		 The  
		 
		 
		 for each conflict:
		   determine winner and loser
		   
		 
		 
		 */
		
		
		// 2. collect conflicts
		// 3. gather winner
		// 4. collect winner
		
	}
	
	private class DatabaseVersionHeaderComparator implements Comparator<DatabaseVersionHeader> {

		@Override
		public int compare(DatabaseVersionHeader header1, DatabaseVersionHeader header2) {
			VectorClockComparison vectorClockComparison = VectorClock.compare(header1.getVectorClock(), header2.getVectorClock());
			
			if (vectorClockComparison == VectorClockComparison.GREATER) {
				return 1;
			}
			else if (vectorClockComparison == VectorClockComparison.SMALLER) {
				return -1;
			}
			else {
				return 0;						
			}
		}
		
	}
	

	private List<DatabaseVersionHeader> readUnknownDatabaseVersionHeaders(List<File> remoteDatabases) throws IOException {
		List<DatabaseVersionHeader> databaseVersionHeaders = new ArrayList<DatabaseVersionHeader>();
						
		DatabaseDAO dbDAO = new DatabaseDAO();
		
		for (File remoteDatabaseInCache : remoteDatabases) {
			Database remoteDatabase = new Database(); // Database cannot be reused, since these might be different clients
			
			dbDAO.load(remoteDatabase, remoteDatabaseInCache);			
			Map<Long, DatabaseVersion> remoteDatabaseVersions = remoteDatabase.getDatabaseVersions();			
			
			for (DatabaseVersion remoteDatabaseVersion : remoteDatabaseVersions.values()) {
				DatabaseVersionHeader header = remoteDatabaseVersion.getHeader();
				databaseVersionHeaders.add(header);
			}
		}
		
		return databaseVersionHeaders;
	}

	private List<RemoteFile> listUnknownRemoteDatabases(Database db, TransferManager transferManager) throws StorageException {
		logger.log(Level.INFO, "Retrieving remote database list.");
		
		List<RemoteFile> unknownRemoteDatabasesList = new ArrayList<RemoteFile>();

		Map<String, RemoteFile> remoteDatabaseFiles = transferManager.list("db-");
		VectorClock knownDatabaseVersions = db.getLastDatabaseVersion().getVectorClock();
		
		for (RemoteFile remoteFile : remoteDatabaseFiles.values()) {
			RemoteDatabaseFile remoteDatabaseFile = new RemoteDatabaseFile(remoteFile.getName());
			
			String clientName = remoteDatabaseFile.getClientName();
			Long knownClientVersion = knownDatabaseVersions.get(clientName);
					
			if (knownClientVersion != null) {
				if (remoteDatabaseFile.getClientVersion() > knownClientVersion) {
					logger.log(Level.INFO, "- Remote database {0} is new.", remoteFile.getName());
					unknownRemoteDatabasesList.add(remoteFile);
				}
				else {
					logger.log(Level.INFO, "- Remote database {0} is already known. Ignoring.", remoteFile.getName());
					// Do nothing. We know this database.
				}
			}
			
			else {
				logger.log(Level.INFO, "- Remote database {0} is new.", remoteFile.getName());
				unknownRemoteDatabasesList.add(remoteFile);
			}				
		}
		
		return unknownRemoteDatabasesList;
	}
	
	private List<File> downloadUnknownRemoteDatabases(TransferManager transferManager, List<RemoteFile> unknownRemoteDatabases) throws StorageException {
		logger.log(Level.INFO, "Downloading unknown databases.");
		List<File> unknownRemoteDatabasesInCache = new ArrayList<File>();
		
		for (RemoteFile remoteFile : unknownRemoteDatabases) {
			File unknownRemoteDatabaseFileInCache = profile.getCache().getDatabaseFile(remoteFile.getName());

			logger.log(Level.INFO, "- Downloading {0} to local cache at {1}", new Object[] { remoteFile.getName(), unknownRemoteDatabaseFileInCache });
			transferManager.download(remoteFile, unknownRemoteDatabaseFileInCache);
			
			unknownRemoteDatabasesInCache.add(unknownRemoteDatabaseFileInCache);
		}
		
		return unknownRemoteDatabasesInCache;
	}		

	private void detectUpdates(Database db, List<File> remoteDatabasesInCache) throws Exception {
		Database newLocalDatabase = db; // TODO shouldn't we clone this in case this goes wrong?
		VectorClock localVectorClock = newLocalDatabase.getLastDatabaseVersion().getVectorClock();

		logger.log(Level.INFO, "Reconciling local database with remote databases ...");
		logger.log(Level.INFO, "- Local database version: {0}", localVectorClock.toString());
		
		VectorClock latestRemoteVectorClock = null;
		File latestRemoteDatabase = null;
		List<File> conflictRemoteDatabases = new ArrayList<File>(); 
		
		for (File remoteDatabaseInCache : remoteDatabasesInCache) {
			logger.log(Level.INFO, "- Processing remote database. Reading from {0} ...", remoteDatabaseInCache);
			
			Database remoteDatabase = new Database();
			DatabaseDAO dbDAO = new DatabaseDAO();
			
			dbDAO.load(remoteDatabase, remoteDatabaseInCache);
			
			VectorClock remoteVectorClock = remoteDatabase.getLastDatabaseVersion().getVectorClock();
			VectorClockComparison localDatabaseIs = VectorClock.compare(localVectorClock, remoteVectorClock);
									
			logger.log(Level.INFO, "  + Success. Remote database version: {0}", remoteVectorClock.toString());

			if (localDatabaseIs == VectorClockComparison.EQUAL) {
				logger.log(Level.INFO, "  + Database versions are equal. Nothing to do.");
			}
			else if (localDatabaseIs == VectorClockComparison.GREATER) {
				logger.log(Level.INFO, "  + Local database is greater. Nothing to do.");
			}
			else if (localDatabaseIs == VectorClockComparison.SMALLER) {
				logger.log(Level.INFO, "  + Local database is SMALLER. Local update needed!");
				
				if (latestRemoteVectorClock != null) {
					VectorClockComparison latestRemoteDatabaseIs = VectorClock.compare(latestRemoteVectorClock, remoteVectorClock);
					
					if (latestRemoteDatabaseIs == VectorClockComparison.SMALLER) {
						latestRemoteDatabase = remoteDatabaseInCache;
						latestRemoteVectorClock = remoteVectorClock;
					}
				}
				//updateLocalDatabase
			}
			else if (localDatabaseIs == VectorClockComparison.SIMULTANEOUS) {
				logger.log(Level.INFO, "  + Databases are SIMULATANEOUS. Reconciliation needed!");
			}
		}
		
		throw new Exception("This is nowhere near done.");
	}	

}
