package com.vetstreet.embedded.jirb;

import javax.annotation.Resource;

import org.jruby.Main;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import com.vetstreet.embedded.jirb.SshServerFactory;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations={"/beans.xml"})
public class JirbServerBootstrapperTest {

	final static Logger log = LoggerFactory.getLogger(JirbServerBootstrapperTest.class);
	
	@Resource
	SshServerFactory factory;
	
	@Before
	public void setUp() throws Exception {
	}

	@After
	public void tearDown() throws Exception {
		
	}
	
	@Test
	public void testJirb() throws Exception {
		System.out.println("STARTED");
		Thread.sleep(10000);		
		factory.stop();
		log.info("Waitin!");
		Thread.sleep(5000);
	}

}
