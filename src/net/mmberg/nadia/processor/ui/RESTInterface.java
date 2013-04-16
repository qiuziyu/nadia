package net.mmberg.nadia.processor.ui;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;

import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.server.ServerConnector;

import com.sun.jersey.multipart.FormDataParam;

import net.mmberg.nadia.processor.NadiaProcessor;
import net.mmberg.nadia.dialogmodel.Dialog;
import net.mmberg.nadia.processor.NadiaProcessorConfig;
import net.mmberg.nadia.processor.store.DialogStore;
import net.mmberg.nadia.processor.ui.UIConsumer.UIConsumerMessage;
import net.mmberg.nadia.processor.ui.UIConsumer.UIConsumerMessage.Meta;


@Path("/")
public class RESTInterface extends UserInterface{

	private Server server;
	private static int instance=-1;
	protected static HashMap<Integer, UIConsumer> instances = new HashMap<Integer, UIConsumer>();
	
	@Context
	UriInfo uri;
	  
	//Web Service Methods
	//===================
	
	@POST
	@Path("dialog")
	public Response createDefaultDialog() throws URISyntaxException, InstantiationException, IllegalAccessException
	{	 
		instance++;
		if (instance>0) create_dialog(instance);
		return init_dialog(instance); //init dialogue, i.e. get first question
	}
	
	
	@POST
	@Path("dialog/load")
	@Produces( MediaType.TEXT_PLAIN )
	@Consumes(MediaType.MULTIPART_FORM_DATA)
	public Response createDialogFromXML(
			@FormDataParam("dialogxml") String dialogxml) throws URISyntaxException, InstantiationException, IllegalAccessException
	{
		instance++;
		create_dialog(instance, dialogxml);		
		return init_dialog(instance); //init dialogue, i.e. get first question
	}
	
	@POST
	@Path("dialog/{instance_id}")
	@Produces( MediaType.TEXT_PLAIN )
	public Response exchange_text(
			@PathParam("instance_id") String instance_id,
			@FormParam("userUtterance") String userUtterance)
	{
		UIConsumerMessage message = process(instance_id, userUtterance);
		String systemUtterance = message.getSystemUtterance();
		
		if (message.getMeta()==Meta.UNCHANGED) return Response.noContent().build();
		else return Response.ok(systemUtterance).build();
	}
	
	@GET
	@Path("dialog/{instance_id}/context")
	@Produces( MediaType.TEXT_PLAIN )
	public Response getDebugInfo(
			@PathParam("instance_id") String instance_id)
	{
		String context=instances.get(Integer.parseInt(instance_id)).getDebugInfo();
		return Response.ok(context).build();
	}

	//Convenience functions
	//=====================
	
	//process user utterance
	private UIConsumerMessage process(String instance_id, String userUtterance){
		consumer=instances.get(Integer.parseInt(instance_id));
		UIConsumerMessage message = consumer.processUtterance(userUtterance);
		return message;
	}
	
	private void create_dialog(int instance, String dialogxml){
		UIConsumer new_consumer;
		if(dialogxml!=null){
			Dialog d=DialogStore.loadFromXml(dialogxml);
			new_consumer = new NadiaProcessor(d);
		}
		else{
			new_consumer = new NadiaProcessor();
		}
		instances.put(instance, new_consumer);
		NadiaProcessor.getLogger().fine("created new instance "+new_consumer.getClass().getName());
	}
	
	private void create_dialog(int instance){
		create_dialog(instance, null);
	}
	
	//init dialogue, i.e. get first question
	private Response init_dialog(int instance) throws URISyntaxException{
		UIConsumerMessage message = process(String.valueOf(instance), ""); //empty utterance => init
		String systemUtterance = message.getSystemUtterance();
		
		if (message.getMeta()==Meta.UNCHANGED) return Response.created(new URI("/"+instance)).build();
		else return Response.created(new URI(uri.getBaseUri()+"dialog/"+instance)).entity(systemUtterance).build();
	}
	
	//Interface functions
	//===================
	
	@Override
	public void start(){
		try{
			instances.put(0, consumer);
			NadiaProcessorConfig config = NadiaProcessorConfig.getInstance();
			
			//Jetty:
			server = new Server();
			
			//main config
	        WebAppContext context = new WebAppContext();
	        context.setDescriptor(config.getProperty(NadiaProcessorConfig.JETTYWEBXMLPATH));
	        context.setResourceBase(config.getProperty(NadiaProcessorConfig.JETTYRESOURCEBASE));
	        context.setContextPath(config.getProperty(NadiaProcessorConfig.JETTYCONTEXTPATH));
	        context.setParentLoaderPriority(true);
	        server.setHandler(context);
	        
	        //ssl (https)
	        SslContextFactory sslContextFactory = new SslContextFactory(config.getProperty(NadiaProcessorConfig.JETTYKEYSTOREPATH));
	        sslContextFactory.setKeyStorePassword(config.getProperty(NadiaProcessorConfig.JETTYKEYSTOREPASS));
	
	        ServerConnector serverconn = new ServerConnector(server, sslContextFactory);
	        serverconn.setPort(8080); //443 (or 80) not allowed on Linux unless run as root
	        server.setConnectors(new Connector[] {serverconn});
	        
	        //start	        
	        server.start();
	        server.join();
			
		}
		catch(Exception ex){
			ex.printStackTrace();
			server.destroy();
		}
	}

	@Override
	public void stop() {
		try {
			server.stop();
		} catch (Exception e) {
			e.printStackTrace();
			server.destroy();
		}
	}

}
