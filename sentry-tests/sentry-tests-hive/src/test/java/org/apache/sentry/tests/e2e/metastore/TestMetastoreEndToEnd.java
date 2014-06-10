/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.sentry.tests.e2e.metastore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.ArrayList;

import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.sentry.tests.e2e.dbprovider.PolicyProviderForTest;
import org.apache.sentry.tests.e2e.hive.Context;
import org.apache.sentry.tests.e2e.hive.StaticUserGroup;
import org.apache.sentry.tests.e2e.hive.hiveserver.HiveServerFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.common.collect.Lists;

public class TestMetastoreEndToEnd extends
    AbstractMetastoreTestWithStaticConfiguration {

  private PolicyProviderForTest policyFile;
  private static final String dbName = "db_1";
  private static final String db_all_role = "all_db1";
  private static final String uri_role = "uri_role";

  @Before
  public void setup() throws Exception {
    context = createContext();
    policyFile = PolicyProviderForTest.setAdminOnServer1(ADMINGROUP);
    policyFile
        .addRolesToGroup(USERGROUP1, db_all_role)
        .addRolesToGroup(USERGROUP2, "read_db_role")
        .addPermissionsToRole(db_all_role, "server=server1->db=" + dbName)
        .addPermissionsToRole("read_db_role",
            "server=server1->db=" + dbName + "->table=*->action=SELECT")
        .setUserGroupMapping(StaticUserGroup.getStaticMapping());
    writePolicyFile(policyFile);

    HiveMetaStoreClient client = context.getMetaStoreClient(ADMIN1);
    client.dropDatabase(dbName, true, true, true);
    createMetastoreDB(client, dbName);
    client.close();

  }

  @After
  public void tearDown() throws Exception {
    if (context != null) {
      context.close();
    }
  }

  /**
   * Setup admin privileges for user ADMIN1 verify user can create DB and tables
   * @throws Exception
   */
  @Test
  public void testServerPrivileges() throws Exception {
    String tabName = "tab1";
    HiveMetaStoreClient client = context.getMetaStoreClient(ADMIN1);
    client.dropDatabase(dbName, true, true, true);

    createMetastoreDB(client, dbName);
    createMetastoreTable(client, dbName, tabName,
        Lists.newArrayList(new FieldSchema("col1", "int", "")));
    assertEquals(1, client.getTables(dbName, tabName).size());
    client.dropTable(dbName, tabName);
    client.dropDatabase(dbName, true, true, true);
  }

  /**
   * verify non-admin user can not create or drop DB
   * @throws Exception
   */
  @Test
  public void testNegativeServerPrivileges() throws Exception {
    HiveMetaStoreClient client = context.getMetaStoreClient(USER1_1);
    try {
      createMetastoreDB(client, "fooDb");
      fail("Creat db should have failed for non-admin user");
    } catch (MetaException e) {
      Context.verifyMetastoreAuthException(e);
    }
    try {
      client.dropDatabase(dbName, true, true, true);
      fail("drop db should have failed for non-admin user");
    } catch (MetaException e) {
      Context.verifyMetastoreAuthException(e);
    }
  }

  /**
   * Verify the user with DB permission can create table in that db Verify the
   * user can't create table in DB where he doesn't have ALL permissions
   * @throws Exception
   */
  @Test
  public void testTablePrivileges() throws Exception {
    String tabName1 = "tab1";
    String tabName2 = "tab2";

    HiveMetaStoreClient client = context.getMetaStoreClient(ADMIN1);
    createMetastoreTable(client, dbName, tabName1,
        Lists.newArrayList(new FieldSchema("col1", "int", "")));
    client.close();

    client = context.getMetaStoreClient(USER1_1);
    createMetastoreTable(client, dbName, tabName2,
        Lists.newArrayList(new FieldSchema("col1", "int", "")));
    assertEquals(1, client.getTables(dbName, tabName2).size());
    client.dropTable(dbName, tabName1);
    client.close();

    client = context.getMetaStoreClient(USER2_1);
    try {
      createMetastoreTable(client, dbName, "barTab",
          Lists.newArrayList(new FieldSchema("col1", "int", "")));
      fail("Create table should have failed for non-privilege user");
    } catch (MetaException e) {
      Context.verifyMetastoreAuthException(e);
    }

    try {
      client.dropTable(dbName, tabName2);
      fail("drop table should have failed for non-privilege user");
    } catch (MetaException e) {
      Context.verifyMetastoreAuthException(e);
    }
    client.close();
  }

  /**
   * Verify alter table privileges
   * @throws Exception
   */
  @Test
  public void testAlterTablePrivileges() throws Exception {
    String tabName1 = "tab1";

    HiveMetaStoreClient client = context.getMetaStoreClient(ADMIN1);
    createMetastoreTable(client, dbName, tabName1,
        Lists.newArrayList(new FieldSchema("col1", "int", "")));
    client.close();

    // verify group1 users with DDL privileges can alter tables in db_1
    client = context.getMetaStoreClient(USER1_1);
    Table metaTable2 = client.getTable(dbName, tabName1);
    metaTable2.getSd().setCols(
        Lists.newArrayList(new FieldSchema("col2", "double", "")));
    client.alter_table(dbName, tabName1, metaTable2);
    Table metaTable3 = client.getTable(dbName, tabName1);
    assertEquals(metaTable2, metaTable3);

    // verify group2 users can't alter tables in db_1
    client = context.getMetaStoreClient(USER2_1);
    metaTable2 = client.getTable(dbName, tabName1);
    metaTable2.getSd().setCols(
        Lists.newArrayList(new FieldSchema("col3", "string", "")));
    try {
      client.alter_table(dbName, tabName1, metaTable2);
      fail("alter table should have failed for non-privilege user");
    } catch (MetaException e) {
      Context.verifyMetastoreAuthException(e);
    }
    client.close();
  }

  /**
   * Verify add partition privileges
   * @throws Exception
   */
  @Test
  public void testAddPartitionPrivileges() throws Exception {
    String tabName = "tab1";
    ArrayList<String> partVals1 = Lists.newArrayList("part1");
    ArrayList<String> partVals2 = Lists.newArrayList("part2");
    ArrayList<String> partVals3 = Lists.newArrayList("part2");

    // user with ALL on DB should be able to add partition
    HiveMetaStoreClient client = context.getMetaStoreClient(USER1_1);
    Table tbl1 = createMetastoreTableWithPartition(client, dbName,
        tabName, Lists.newArrayList(new FieldSchema("col1", "int", "")),
        Lists.newArrayList(new FieldSchema("part_col1", "string", "")));
    assertEquals(1, client.getTables(dbName, tabName).size());
    addPartition(client, dbName, tabName, partVals1, tbl1);
    addPartition(client, dbName, tabName, partVals2, tbl1);
    client.close();

    // user without ALL on DB should NOT be able to add partition
    client = context.getMetaStoreClient(USER2_1);
    try {
      addPartition(client, dbName, tabName, partVals3, tbl1);
      fail("Add partition should have failed for non-admin user");
    } catch (MetaException e) {
      Context.verifyMetastoreAuthException(e);
    }
    client.close();

    // user with ALL on DB should be able to drop partition
    client = context.getMetaStoreClient(USER1_1);
    tbl1 = client.getTable(dbName, tabName);
    client.dropPartition(dbName, tabName, partVals1, true);
    client.close();

    // user without ALL on DB should NOT be able to drop partition
    client = context.getMetaStoreClient(USER2_1);
    try {
      addPartition(client, dbName, tabName, partVals2, tbl1);
      fail("Drop partition should have failed for non-admin user");
    } catch (MetaException e) {
      Context.verifyMetastoreAuthException(e);
    }
  }

  /**
   * Verify URI privileges for alter table table
   * @throws Exception
   */
  @Test
  public void testUriTablePrivileges() throws Exception {
    String tabName1 = "tab1";
    String tabName2 = "tab2";
    String newPath1 = "fooTab1";
    String newPath2 = "fooTab2";

    String tabDir1 = hiveServer.getProperty(HiveServerFactory.WAREHOUSE_DIR)
        + File.separator + newPath1;
    String tabDir2 = hiveServer.getProperty(HiveServerFactory.WAREHOUSE_DIR)
        + File.separator + newPath2;
    policyFile.addRolesToGroup(USERGROUP1, uri_role)
        .addRolesToGroup(USERGROUP2, db_all_role)
        .addPermissionsToRole(uri_role, "server=server1->URI=" + tabDir1)
        .addPermissionsToRole(uri_role, "server=server1->URI=" + tabDir2);
    writePolicyFile(policyFile);

    // create table
    HiveMetaStoreClient client = context.getMetaStoreClient(USER2_1);
    createMetastoreTable(client, dbName, tabName1,
        Lists.newArrayList(new FieldSchema("col1", "int", "")));
    client.close();

    // user with URI privileges should be able to create table with that specific location
    client = context.getMetaStoreClient(USER1_1);
    createMetastoreTableWithLocation(client, dbName, tabName2,
        Lists.newArrayList(new FieldSchema("col1", "int", "")), tabDir2);
    client.close();

    // user without URI privileges should be NOT able to create table with that specific location
    client = context.getMetaStoreClient(USER2_1);
    try {
      createMetastoreTableWithLocation(client, dbName, tabName2,
          Lists.newArrayList(new FieldSchema("col1", "int", "")), tabDir2);
      fail("Create table with location should fail without URI privilege");
    } catch (MetaException e) {
      Context.verifyMetastoreAuthException(e);
    }
    client.close();

    // user with URI privileges should be able to alter table to set that specific location
    client = context.getMetaStoreClient(USER1_1);
    Table metaTable1 = client.getTable(dbName, tabName1);
    metaTable1.getSd().setLocation(tabDir1);
    client.alter_table(dbName, tabName1, metaTable1);
    client.close();

    // user without URI privileges should be NOT able to alter table to set that
    // specific location
    client = context.getMetaStoreClient(USER2_1);
    Table metaTable2 = client.getTable(dbName, tabName2);
    metaTable1.getSd().setLocation(tabDir1);
    try {
      client.alter_table(dbName, tabName1, metaTable2);
      fail("Alter table with location should fail without URI privilege");
    } catch (MetaException e) {
      Context.verifyMetastoreAuthException(e);
    }
    client.close();
  }

  /**
   * Verify URI privileges for alter table table
   * @throws Exception
   */
  @Test
  public void testUriPartitionPrivileges() throws Exception {
    String tabName1 = "tab1";
    String newPath1 = "fooTab1";
    String newPath2 = "fooTab2";
    ArrayList<String> partVals1 = Lists.newArrayList("part1");
    ArrayList<String> partVals2 = Lists.newArrayList("part2");
    ArrayList<String> partVals3 = Lists.newArrayList("part2");

    String tabDir1 = hiveServer.getProperty(HiveServerFactory.WAREHOUSE_DIR)
        + File.separator + newPath1;
    String tabDir2 = hiveServer.getProperty(HiveServerFactory.WAREHOUSE_DIR)
        + File.separator + newPath2;
    policyFile.addRolesToGroup(USERGROUP1, uri_role)
        .addRolesToGroup(USERGROUP2, db_all_role)
        .addPermissionsToRole(uri_role, "server=server1->URI=" + tabDir1)
        .addPermissionsToRole(uri_role, "server=server1->URI=" + tabDir2);
    writePolicyFile(policyFile);


    // user with URI privileges should be able to alter partition to set that specific location
    HiveMetaStoreClient client = context.getMetaStoreClient(USER1_1);
    Table tbl1 = createMetastoreTableWithPartition(client, dbName,
        tabName1, Lists.newArrayList(new FieldSchema("col1", "int", "")),
        Lists.newArrayList(new FieldSchema("part_col1", "string", "")));
    addPartition(client, dbName, tabName1, partVals1, tbl1);
    addPartitionWithLocation(client, dbName, tabName1, partVals2, tbl1,
        tabDir1);
    client.close();

    // user without URI privileges should be NOT able to alter partition to set
    // that specific location
    client = context.getMetaStoreClient(USER2_1);
    try {
      tbl1 = client.getTable(dbName, tabName1);
      addPartitionWithLocation(client, dbName, tabName1, partVals3,
          tbl1, tabDir2);
    } catch (MetaException e) {
      Context.verifyMetastoreAuthException(e);
    }
    client.close();
  }

  /**
   * Verify alter partion privileges
   * TODO: We seem to have a bit inconsistency with Alter partition. It's only
   * allowed with SERVER privilege. If we allow add/drop partition with DB
   * level privilege, then this should also be at the same level.
   * @throws Exception
   */
  @Test
  public void testAlterSetLocationPrivileges() throws Exception {
    String tabName1 = "tab1";
    ArrayList<String> partVals1 = Lists.newArrayList("part1");

    // user with Server privileges should be able to alter partition
    HiveMetaStoreClient client = context.getMetaStoreClient(ADMIN1);
    Table tbl1 = createMetastoreTableWithPartition(client, dbName,
        tabName1, Lists.newArrayList(new FieldSchema("col1", "int", "")),
        Lists.newArrayList(new FieldSchema("part_col1", "string", "")));
    addPartition(client, dbName, tabName1, partVals1, tbl1);
    Partition newPartition = client.getPartition(dbName, tabName1, partVals1);
    client.alter_partition(dbName, tabName1, newPartition);
    client.close();

    // user without SERVER privileges should be able to alter partition to set
    // that specific location
    client = context.getMetaStoreClient(USER1_1);
    tbl1 = client.getTable(dbName, tabName1);
    newPartition = client.getPartition(dbName, tabName1, partVals1);
    try {
      client.alter_partition(dbName, tabName1, newPartition);
    } catch (MetaException e) {
      Context.verifyMetastoreAuthException(e);
    }
    client.close();

  }

}