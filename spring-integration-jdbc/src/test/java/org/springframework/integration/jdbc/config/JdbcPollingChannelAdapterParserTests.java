package org.springframework.integration.jdbc.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.After;
import org.junit.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.integration.channel.MessageChannelTemplate;
import org.springframework.integration.channel.PollableChannel;
import org.springframework.integration.core.Message;
import org.springframework.jdbc.core.namedparam.AbstractSqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@Transactional
public class JdbcPollingChannelAdapterParserTests {
	
	
	final long receiveTimeout = 5000;
	
	private SimpleJdbcTemplate jdbcTemplate;
	
	private MessageChannelTemplate channelTemplate;
	
	private ConfigurableApplicationContext appCtx;

	private PlatformTransactionManager transactionManager;
		
	@Test
	public void testSimpleInboundChannelAdapter(){
		setUp("pollingForMapJdbcInboundChannelAdapterTest.xml", getClass());
		this.jdbcTemplate.update("insert into item values(1,'',2)");
		Message<?> message = channelTemplate.receive();
		assertNotNull("No message found ", message);
		assertTrue("Wrong payload type expected instance of List", message.getPayload() instanceof List<?>);
	}
	
	
	@Test
	public void testSimpleInboundChannelAdapterWithUpdate(){
		setUp("pollingForMapJdbcInboundChannelAdapterWithUpdateTest.xml", getClass());
		this.jdbcTemplate.update("insert into item values(1,'',2)");
		Message<?> message = channelTemplate.receive();
		assertNotNull(message);
		message = channelTemplate.receive();
		assertNull(channelTemplate.receive());
	}
	
	@Test
	public void testSimpleInboundChannelAdapterWithNestedUpdate(){
		setUp("pollingForMapJdbcInboundChannelAdapterWithNestedUpdateTest.xml", getClass());
		this.jdbcTemplate.update("insert into item values(1,'',2)");
		Message<?> message = channelTemplate.receive();
		assertNotNull(message);
		message = channelTemplate.receive();
		assertNull(channelTemplate.receive());
	}
	
	@Test
	public void testExtendedInboundChannelAdapter(){
		setUp("pollingWithJdbcOperationsJdbcInboundChannelAdapterTest.xml", getClass());
		this.jdbcTemplate.update("insert into item values(1,'',2)");
		Message<?> message = channelTemplate.receive();
		assertNotNull(message);
	}
	
	@Test
	public void testParameterSourceFactoryInboundChannelAdapter(){
		setUp("pollingWithParameterSourceJdbcInboundChannelAdapterTest.xml", getClass());
		this.jdbcTemplate.update("insert into item values(1,'',2)");
		Message<?> message = channelTemplate.receive();
		assertNotNull(message);
		List<Map<String, Object>> list = jdbcTemplate.queryForList("SELECT * FROM item WHERE status=1");
		assertEquals(1, list.size());
		assertEquals("bar", list.get(0).get("NAME"));
	}
	
	@Test
	public void testParameterSourceInboundChannelAdapter(){
		setUp("pollingWithParametersForMapJdbcInboundChannelAdapterTest.xml", getClass());
		this.jdbcTemplate.update("insert into item values(1,'',2)");
		Message<?> message = channelTemplate.receive();
		assertNotNull(message);
	}
	
	@Test
	public void testMaxRowsInboundChannelAdapter(){
		setUp("pollingWithMaxRowsJdbcInboundChannelAdapterTest.xml", getClass());
		new TransactionTemplate(transactionManager).execute(new TransactionCallback<Void>() {
			public Void doInTransaction(TransactionStatus status) {
				jdbcTemplate.update("insert into item values(1,'',2)");
				jdbcTemplate.update("insert into item values(2,'',2)");
				jdbcTemplate.update("insert into item values(3,'',2)");
				jdbcTemplate.update("insert into item values(4,'',2)");
				return null;
			}
		});
		@SuppressWarnings("unchecked")
		Message<List<?>> message = (Message<List<?>>) channelTemplate.receive();
		assertNotNull(message);
		assertEquals(2, message.getPayload().size());
	}
	
	@After
	public void tearDown(){
		if(appCtx != null){
			appCtx.close();
		}
	}
	
	public void setUp(String name, Class<?> cls){
		appCtx = new ClassPathXmlApplicationContext(name, cls);
		setupJdbcTemplate();
		jdbcTemplate.update("delete from item");
		setupTransactionManager();
		setupMessageChannelTemplate();
	}
	
	
	protected void setupMessageChannelTemplate(){
		PollableChannel pollableChannel = this.appCtx.getBean("target", PollableChannel.class);
		this.channelTemplate =  new MessageChannelTemplate(pollableChannel);
		this.channelTemplate.setReceiveTimeout(500);
	}
	
	protected void setupJdbcTemplate(){
		this.jdbcTemplate = new SimpleJdbcTemplate(this.appCtx.getBean("dataSource",DataSource.class));
	}
	
	protected void setupTransactionManager(){
		this.transactionManager = this.appCtx.getBean("transactionManager",PlatformTransactionManager.class);
	}
	
	public static class TestSqlParameterSource extends AbstractSqlParameterSource {

		public Object getValue(String paramName)
				throws IllegalArgumentException {
			return 2;
		}

		public boolean hasValue(String paramName) {
			return true;
		}
		
	}

}
