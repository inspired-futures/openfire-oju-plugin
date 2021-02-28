package org.ifsoft.websockets;

import org.jivesoftware.util.JiveGlobals;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.security.*;
import java.util.*;
import java.text.*;
import java.net.*;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.api.annotations.*;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;

import org.jivesoftware.util.ParamUtils;

import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.MessageRouter;
import org.jivesoftware.openfire.muc.*;

import net.sf.json.*;
import org.xmpp.packet.*;
import org.ifsoft.oju.openfire.Oju;

@WebSocket public class ProxyWebSocket
{
    private static Logger Log = LoggerFactory.getLogger( "ProxyWebSocket" );
    private Session wsSession;
    private ProxyConnection proxyConnection;
	private XmppConnection xmpp = null;
	private String room_name = null;
	private String domain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
	private MessageRouter messageRouter = XMPPServer.getInstance().getMessageRouter();
	
	public String username = "";	
	public HashMap<String, String> participants = new HashMap<String, String>();

    public void setProxyConnection(ProxyConnection proxyConnection) {
        this.proxyConnection = proxyConnection;
        proxyConnection.setSocket(this);
        Log.debug("setProxyConnection");
    }

    public boolean isOpen() {
        return wsSession.isOpen();
    }

    @OnWebSocketConnect public void onConnect(Session wsSession)
    {
        this.wsSession = wsSession;
		room_name = null;
		username = "";
        //proxyConnection.setSecure(wsSession.isSecure());
        Log.debug("onConnect");
    }

    @OnWebSocketClose public void onClose(int statusCode, String reason)
    {
        try {
            proxyConnection.disconnect();

        } catch ( Exception e ) {
            Log.error( "An error occurred while attempting to remove the socket", e );
        }

        Log.debug(" : onClose : " + statusCode + " " + reason);
    }

    @OnWebSocketError public void onError(Throwable error)
    {
        Log.error("ProxyWebSocket onError", error);
    }

    @OnWebSocketMessage public void onTextMethod(String data)
    {
		// join
		// {"type":"join","kind":"join","group":"lobby","username":"","password":""}
		
		// participant added/deleted
		// {"type":"user","kind":"add","id":"3e86d28b16c6e51160ac7ab4108ef51d","username":"femi"}
		// {"type":"user","kind":"delete","id":"3e86d28b16c6e51160ac7ab4108ef51d","username":"femi"}		
		
		// from anon to all
		// {"type":"chat","source":"3408dd7c44ecf90dbfa5327189009a5c","dest":"","kind":"","value":"hello"}
		
		// from femi to all
		// {"type":"chat","source":"0226ce2742c8fdfb5729ee0af67b7d55","dest":"","username":"femi","kind":"","value":"aaaaaaaaaaaaaaaaaaaa"}
		
		// from femi to sade
		// {"type":"chat","source":"0226ce2742c8fdfb5729ee0af67b7d55","dest":"b92132f59e34c0692462443ba17acc40","username":"femi","kind":"","value":"hello"}
		
		// from sade to femi
		// {"type":"chat","source":"b92132f59e34c0692462443ba17acc40","dest":"0226ce2742c8fdfb5729ee0af67b7d55","username":"sade","kind":"","value":"Excellent"}
        try {
            Log.debug(" : onMessage : Received : \n" + data );
            proxyConnection.deliver(data);
			
			JSONObject json = new JSONObject(data);	
			String type = json.getString("type");

			if ("join".equals(type))
			{
				room_name = json.getString("group");
				username = json.getString("username");
				
				if (!username.isEmpty())
				{
					Log.debug("storing socket for " + room_name + username);
					Oju.self.websockets.put(room_name + username, this);
					xmpp = new XmppConnection(this, username);	
					xmpp.route("<presence />");					
				}
			}
			else
				
			if ("user".equals(type))
			{
				String user = json.getString("username");	
				String id = json.getString("id");
				
				if ("add".equals(json.getString("kind")))
				{
					Log.debug("storing participant for " + id + user);					
					participants.put(id, user);
					participants.put(user, id);					
				}
				
				if ("delete".equals(json.getString("kind")))
				{
					Log.debug("removing participant for " + id + user);						
					participants.remove(user);							
					participants.remove(id);						
				}
			}
			else				

			if ("chat".equals(type) && room_name != null)
			{
				String source = json.getString("source");
				String dest = json.getString("dest");
				String value = json.getString("value");	
				String service = "conference";
				
				if (!username.isEmpty())
				{
					Message message = new Message();	
					message.setBody(value);					

					if (dest.isEmpty())
					{					
						MultiUserChatService mucService = XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatService(service);
						MUCRoom room = mucService.getChatRoom(room_name);
						
						if (room != null)
						{
							message.setType(Message.Type.groupchat);							
							message.setFrom(room_name + "@" + service + "." + domain + "/" + username);
							room.send(message, room.getRole());	
						}							
					} else {
						if (participants.containsKey(dest))
						{
							String peer = participants.get(dest);
							message.setType(Message.Type.chat);								
							message.setTo(peer + "@" + domain);
							message.setFrom(username + "@" + domain);
							messageRouter.route(message);
						}
					}		
				}					
			}							

        } catch ( Exception e ) {
            Log.error( "An error occurred while attempting to route the packet : ", e );
        }
    }

    @OnWebSocketMessage public void onBinaryMethod(byte data[], int offset, int length)
    {
     // simple BINARY message received
    }

    public void deliver(String message)
    {
		// {"type":"chat","source":"0226ce2742c8fdfb5729ee0af67b7d55","username":"femi","value":"aaaaaaaaaaaaaaaaaaaa","time":1613648989119}		
        try {
            Log.debug(" : Delivered : \n" + message );
            wsSession.getRemote().sendStringByFuture(message);
        } catch (Exception e) {
            Log.error("ProxyWebSocket deliver " + e);
            Log.warn("Could not deliver : \n" + message );
        }
    }

    public void disconnect()
    {
        Log.debug("disconnect : ProxyWebSocket disconnect");

        try {
            if (wsSession != null && wsSession.isOpen())
            {
                wsSession.close();
            }
        } catch ( Exception e ) {

            try {
                wsSession.disconnect();
            } catch ( Exception e1 ) {

            }
        }
		
		if (!username.isEmpty())
		{
			Oju.self.websockets.remove(room_name + username);		
		}

		if (xmpp != null) xmpp.close();		
    }
}