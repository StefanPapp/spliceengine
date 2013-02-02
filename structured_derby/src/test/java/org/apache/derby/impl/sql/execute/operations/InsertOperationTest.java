package org.apache.derby.impl.sql.execute.operations;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.junit.*;

import com.google.common.collect.Maps;
import com.splicemachine.derby.test.DerbyTestRule;
import com.splicemachine.derby.test.SpliceDerbyTest;
import com.splicemachine.utils.SpliceLogUtils;

public class InsertOperationTest {
	private static final Logger LOG = Logger.getLogger(InsertOperationTest.class);
	
	private static final Map<String,String> tableMap;
	static{
		Map<String,String> tMap = new HashMap<String,String>();
		tMap.put("t","name varchar(40)");
		tMap.put("a","name varchar(40)");
		tMap.put("s","name varchar(40), count int");
		
		tableMap = tMap;
	}
	@Rule public DerbyTestRule rule = new DerbyTestRule(tableMap,LOG);

	@BeforeClass
	public static void startup() throws Exception{
		LOG.debug("Starting up embedded connection");
		DerbyTestRule.start();
		LOG.debug("EmbeddedConnection started successfully");
	}
	
	@AfterClass
	public static void shutdown() throws Exception{
		LOG.debug("Shutting down embedded connection");
		DerbyTestRule.shutdown();
		LOG.debug("Embedded connection shutdown successfully");
	}
	
	@Before
	public void setUpTest() throws Exception{
	}
	
	
	@Test
	public void testInsertMultipleRecords() throws Exception{
		Statement s = null;
		ResultSet rs = null;
//		try{
			s = rule.getStatement();
			s.execute("insert into t (name) values ('gdavis'),('mzweben'),('rreimer')");
			List<String> correctNames = Arrays.asList("gdavis","mzweben","rreimer");
			Collections.sort(correctNames);
			rs = rule.executeQuery("select * from t");
			List<String> names = new ArrayList<String>();
			while(rs.next()){
				names.add(rs.getString(1));
			}
			
			Collections.sort(names);
			Assert.assertEquals("returned named incorrect!",correctNames,names);
//		}finally{
//			if(s!=null)s.close();
//		}
	}
	
	@Test
	public void testInsertSingleRecord() throws Exception{
		Statement s = null;
		ResultSet rs = null;
//		try{
			s = rule.getStatement();
			s.execute("insert into t (name) values ('gdavis')");
			s.execute("insert into a values('sfines')");
			s.execute("insert into a values('jzhang')");
			s.execute("insert into a values('jleach')");
			List<String> correctNames = Arrays.asList("gdavis");
			Collections.sort(correctNames);
			rs = SpliceDerbyTest.executeQuery("select * from t");
			List<String> names = new ArrayList<String>();
			while(rs.next()){
				names.add(rs.getString(1));
			}
			
			Collections.sort(names);
			Assert.assertEquals("returned named incorrect!",correctNames,names);
			rule.commit();
//		}finally{ if(s!=null)s.close();
//		}
	}

	@Test
	public void testInsertFromSubselect() throws Exception{
		//insert data into table a
		Statement s = null;
		try{
			s = rule.getStatement();
			s.execute("insert into a values('sfines')");
			s.execute("insert into a values('jzhang')");
			s.execute("insert into a values('jleach')");
			rule.commit();
		}finally{
			if(s!=null)s.close();
		}	
		List<String> correctNames = Arrays.asList("sfines","jzhang","jleach");
		Collections.sort(correctNames);
		//copy that data into table t
		ResultSet rs = null;
		try{
			s = rule.getStatement();
			s.execute("insert into t (name) select name from a");
			rule.commit();
			
			rs = SpliceDerbyTest.executeQuery("select * from t");
			List<String> names = new ArrayList<String>();
			while(rs.next()){
				LOG.info("name="+rs.getString(1));
				names.add(rs.getString(1));
			}
			
			Collections.sort(names);
			Assert.assertEquals("returned named incorrect!",correctNames,names);
			rule.commit();
		}finally{
			if(s!=null)s.close();
		}
	}

	@Test
	public void testInsertReportsCorrectReturnedNumber() throws Exception{
		PreparedStatement ps = rule.prepareStatement("insert into t (name) values (?)");
		ps.setString(1,"bob");

		int returned = ps.executeUpdate();
		Assert.assertEquals("incorrect update count returned!",1,returned);
	}

	@Test
    @Ignore("Ignoring until joins are handled properly")
	public void testInsertFromBoundedSubSelectThatChanges() throws Exception{
		/*
		 *The idea here is to test that PreparedStatement inserts won't barf if you do
		 *multiple inserts with different where clauses each time 
		 */
		PreparedStatement ps = rule.prepareStatement("insert into t (name) select name from a where a.name = ?");
		ps.setString(1,"jzhang");
		ps.executeUpdate();

		ResultSet rs = rule.executeQuery("select * from t");
		int count=0;
		while(rs.next()){
			Assert.assertEquals("Incorrect name inserted!","jzhang",rs.getString(1));
			count++;
		}
		Assert.assertEquals("Incorrect number of results returned!",1,count);
		
		ps.setString(1,"jleach");
		ps.executeUpdate();
		List<String> correct = Arrays.asList("jzhang","jleach");
		
		rs = rule.executeQuery("select * from t");
		count=0;
		while(rs.next()){
			String next = rs.getString(1);
			boolean found=false;
			for(String correctName:correct){
				if(correctName.equals(next)){
					found=true;
					break;
				}
			}
			Assert.assertTrue("Value "+ next+" unexpectedly appeared!",found);
			count++;
		}
		Assert.assertEquals("Incorrect number of results returned!",correct.size(),count);
	}
	
	@Test
	public void testInsertFromSubOperation() throws Exception{
		//insert data into table a
		Statement s = null;
		Map<String,Integer> nameCountMap = Maps.newHashMap();
		try{
			s = rule.getStatement();
			s.execute("insert into a values('sfines')");
			s.execute("insert into a values('sfines')");
			nameCountMap.put("sfines",2);
			s.execute("insert into a values('jzhang')");
			s.execute("insert into a values('jzhang')");
			s.execute("insert into a values('jzhang')");
			nameCountMap.put("jzhang", 3);
			s.execute("insert into a values('jleach')");
			nameCountMap.put("jleach",1);
			rule.commit();
		}finally{
			if(s!=null)s.close();
		}
		rule.splitTable("a");

		//copy that data into table t
		ResultSet rs = null;
		try{
			s = rule.getStatement();
			int returned = s.executeUpdate("insert into s (name,count) select name,count(name) from a group by name");
			LOG.warn(String.format("inserted %d rows",returned));
			rule.commit();
			
			rs = SpliceDerbyTest.executeQuery("select * from s");
			int groupCount=0;
			while(rs.next()){
				String name = rs.getString(1);
				Integer count = rs.getInt(2);
				Assert.assertNotNull("Name is null!",name);
				Assert.assertNotNull("Count is null!",count);
				
				int correctCount = nameCountMap.get(name);
				Assert.assertEquals("Incorrect count returned for name "+name,correctCount,count.intValue());
				groupCount++;
			}
			Assert.assertEquals("Incorrect number of groups returned!",nameCountMap.size(),groupCount);
		}finally{
			if(rs!=null)rs.close();
			try{
				s.execute("drop table s");
				rule.commit();
			}finally{
				if(s!=null)s.close();
			}
		}	
	}
	
	@Test
	public void testExecuteUpdateMultipleEntries() throws Exception {
		PreparedStatement ps = rule.prepareStatement("insert into s (name,count) values (?,?)");
		
		Map<String,Integer> correctMap = Maps.newHashMap();
		SpliceLogUtils.trace(LOG,"inserting record #1");
		ps.setString(1,"test");
		ps.setInt(2, 10);
		ps.executeUpdate();
		correctMap.put("test",10);
		rule.commit();
		
		SpliceLogUtils.trace(LOG,"inserting record #2");
		ps.setString(1, "test2");
		ps.setInt(2,20);
		ps.executeUpdate();
		correctMap.put("test2",20);
		rule.commit();
		
		SpliceLogUtils.trace(LOG,"Finished inserting data, checking table");
		ResultSet rs = rule.executeQuery("select * from s");
		int count =0;
		while(rs.next()){
			int correctCount = correctMap.get(rs.getString(1));
			Assert.assertEquals("Incorrect count for key "+ rs.getString(1),correctCount, rs.getInt(2));
			count++;
		}
		Assert.assertEquals("Incorrect number of elements inserted!",correctMap.size(),count);
	}
	
}
