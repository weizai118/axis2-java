/*                                                                             
 * Copyright 2004,2005 The Apache Software Foundation.                         
 *                                                                             
 * Licensed under the Apache License, Version 2.0 (the "License");             
 * you may not use this file except in compliance with the License.            
 * You may obtain a copy of the License at                                     
 *                                                                             
 *      http://www.apache.org/licenses/LICENSE-2.0                             
 *                                                                             
 * Unless required by applicable law or agreed to in writing, software         
 * distributed under the License is distributed on an "AS IS" BASIS,           
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.    
 * See the License for the specific language governing permissions and         
 * limitations under the License.                                              
 */
package org.apache.axis2.clustering.tribes;

import org.apache.axis2.clustering.ClusteringFault;
import org.apache.axis2.clustering.Member;
import org.apache.axis2.clustering.MembershipScheme;
import org.apache.axis2.clustering.control.wka.JoinGroupCommand;
import org.apache.axis2.clustering.control.wka.MemberJoinedCommand;
import org.apache.axis2.clustering.control.wka.MemberListCommand;
import org.apache.axis2.description.Parameter;
import org.apache.axis2.util.Utils;
import org.apache.catalina.tribes.Channel;
import org.apache.catalina.tribes.ChannelException;
import org.apache.catalina.tribes.ManagedChannel;
import org.apache.catalina.tribes.RemoteProcessException;
import org.apache.catalina.tribes.group.Response;
import org.apache.catalina.tribes.group.RpcCallback;
import org.apache.catalina.tribes.group.RpcChannel;
import org.apache.catalina.tribes.group.interceptors.OrderInterceptor;
import org.apache.catalina.tribes.group.interceptors.StaticMembershipInterceptor;
import org.apache.catalina.tribes.group.interceptors.TcpFailureDetector;
import org.apache.catalina.tribes.group.interceptors.TcpPingInterceptor;
import org.apache.catalina.tribes.membership.StaticMember;
import org.apache.catalina.tribes.transport.ReceiverBase;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.IOException;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Implementation of the WKA(well-known address) based membership scheme. In this scheme,
 * membership is discovered using a few well-known members (who run at well-known IP addresses)
 */
public class WkaBasedMembershipScheme implements MembershipScheme {

    private static final Log log = LogFactory.getLog(WkaBasedMembershipScheme.class);

    /**
     * The Tribes channel
     */
    private ManagedChannel channel;
    private RpcChannel rpcChannel;
    private MembershipManager primaryMembershipManager;
    private List<MembershipManager> applicationDomainMembershipManagers;
    private StaticMembershipInterceptor staticMembershipInterceptor;
    private Map<String, Parameter> parameters;

    /**
     * The loadBalancerDomain to which the members belong to
     */
    private byte[] domain;

    /**
     * The static(well-known) members
     */
    private List<Member> members;

    /**
     * The mode in which this member operates such as "loadBalance" or "application"
     */
    private Mode mode;

    public WkaBasedMembershipScheme(ManagedChannel channel,
                                    Mode mode,
                                    List<MembershipManager> applicationDomainMembershipManagers,
                                    RpcChannel rpcChannel,
                                    MembershipManager primaryMembershipManager,
                                    Map<String, Parameter> parameters,
                                    byte[] domain,
                                    List<Member> members) {
        this.channel = channel;
        this.mode = mode;
        this.applicationDomainMembershipManagers = applicationDomainMembershipManagers;
        this.rpcChannel = rpcChannel;
        this.primaryMembershipManager = primaryMembershipManager;
        this.parameters = parameters;
        this.domain = domain;
        this.members = members;
    }

    /**
     * Configure the membership related to the WKA based scheme
     *
     * @throws org.apache.axis2.clustering.ClusteringFault
     *          If an error occurs while configuring this scheme
     */
    public void init() throws ClusteringFault {
        addInterceptors();
        configureStaticMembership();
    }

    private void configureStaticMembership() throws ClusteringFault {
        channel.setMembershipService(new WkaMembershipService(primaryMembershipManager));
        StaticMember localMember = new StaticMember();
        primaryMembershipManager.setLocalMember(localMember);
        ReceiverBase receiver = (ReceiverBase) channel.getChannelReceiver();

        // ------------ START: Configure and add the local member ---------------------
        Parameter localHost = getParameter(TribesConstants.LOCAL_MEMBER_HOST);
        String host;
        if (localHost != null) {
            host = ((String) localHost.getValue()).trim();
        } else { // In cases where the localhost needs to be automatically figured out
            try {
                try {
                    host = Utils.getIpAddress();
                } catch (SocketException e) {
                    String msg = "Could not get local IP address";
                    log.error(msg, e);
                    throw new ClusteringFault(msg, e);
                }
            } catch (Exception e) {
                String msg = "Could not get the localhost name";
                log.error(msg, e);
                throw new ClusteringFault(msg, e);
            }
        }
        receiver.setAddress(host);
        try {
            localMember.setHostname(host);
        } catch (IOException e) {
            String msg = "Could not set the local member's name";
            log.error(msg, e);
            throw new ClusteringFault(msg, e);
        }

        Parameter localPort = getParameter(TribesConstants.LOCAL_MEMBER_PORT);
        int port;
        try {
            if (localPort != null) {
                port = Integer.parseInt(((String) localPort.getValue()).trim());
                port = getLocalPort(new ServerSocket(), localMember.getHostname(), port, 4000, 100);
            } else { // In cases where the localport needs to be automatically figured out
                port = getLocalPort(new ServerSocket(), localMember.getHostname(), -1, 4000, 100);
            }
        } catch (IOException e) {
            String msg =
                    "Could not allocate the specified port or a port in the range 4000-4100 " +
                    "for local host " + localMember.getHostname() +
                    ". Check whether the IP address specified or inferred for the local " +
                    "member is correct.";
            log.error(msg, e);
            throw new ClusteringFault(msg, e);
        }

        byte[] payload = "ping".getBytes();
        localMember.setPayload(payload);
        receiver.setPort(port);
        localMember.setPort(port);
        localMember.setDomain(domain);
        staticMembershipInterceptor.setLocalMember(localMember);

        // ------------ END: Configure and add the local member ---------------------

        // ------------ START: Add other members ---------------------
        for (Member member : members) {
            StaticMember tribesMember;
            try {
                tribesMember = new StaticMember(member.getHostName(), member.getPort(),
                                                0, payload);
            } catch (IOException e) {
                String msg = "Could not add static member " +
                             member.getHostName() + ":" + member.getPort();
                log.error(msg, e);
                throw new ClusteringFault(msg, e);
            }

            // Do not add the local member to the list of members
            if (!(Arrays.equals(localMember.getHost(), tribesMember.getHost()) &&
                  localMember.getPort() == tribesMember.getPort())) {
                tribesMember.setDomain(domain);

                // We will add the member even if it is offline at this moment. When the
                // member comes online, it will be detected by the GMS
                staticMembershipInterceptor.addStaticMember(tribesMember);
                primaryMembershipManager.addWellKnownMember(tribesMember);
                if (canConnect(member)) {
                    primaryMembershipManager.memberAdded(tribesMember);
                    log.info("Added static member " + TribesUtil.getName(tribesMember));
                } else {
                    log.info("Could not connect to member " + TribesUtil.getName(tribesMember));
                }
            }
        }
    }

    /**
     * Before adding a static member, we will try to verify whether we can connect to it
     *
     * @param member The member whose connectvity needs to be verified
     * @return true, if the member can be contacted; false, otherwise.
     */
    private boolean canConnect(org.apache.axis2.clustering.Member member) {
        for (int retries = 5; retries > 0; retries--) {
            try {
                InetAddress addr = InetAddress.getByName(member.getHostName());
                SocketAddress sockaddr = new InetSocketAddress(addr,
                                                               member.getPort());
                new Socket().connect(sockaddr, 500);
                return true;
            } catch (IOException e) {
                String msg = e.getMessage();
                if (msg.indexOf("Connection refused") == -1 && msg.indexOf("connect timed out") == -1) {
                    log.error("Cannot connect to member " +
                              member.getHostName() + ":" + member.getPort(), e);
                }
            }
        }
        return false;
    }

    protected int getLocalPort(ServerSocket socket, String hostname,
                               int preferredPort, int portstart, int retries) throws IOException {
        if (preferredPort != -1) {
            try {
                return getLocalPort(socket, hostname, preferredPort);
            } catch (IOException ignored) {
                // Fall through and try a default port
            }
        }
        InetSocketAddress addr = null;
        if (retries > 0) {
            try {
                return getLocalPort(socket, hostname, portstart);
            } catch (IOException x) {
                retries--;
                if (retries <= 0) {
                    log.error("Unable to bind server socket to:" + addr + " throwing error.");
                    throw x;
                }
                portstart++;
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                    ignored.printStackTrace();
                }
                getLocalPort(socket, hostname, portstart, retries, -1);
            }
        }
        return portstart;
    }

    private int getLocalPort(ServerSocket socket, String hostname, int port) throws IOException {
        InetSocketAddress addr;
        addr = new InetSocketAddress(hostname, port);
        socket.bind(addr);
        log.info("Receiver Server Socket bound to:" + addr);
        socket.setSoTimeout(5);
        socket.close();
        try {
            Thread.sleep(100);
        } catch (InterruptedException ignored) {
            ignored.printStackTrace();
        }
        return port;
    }

    /**
     * Add ChannelInterceptors. The order of the interceptors that are added will depend on the
     * membership management scheme
     */
    private void addInterceptors() {

        if (log.isDebugEnabled()) {
            log.debug("Adding Interceptors...");
        }
        TcpPingInterceptor tcpPingInterceptor = new TcpPingInterceptor();
        tcpPingInterceptor.setInterval(100);
        channel.addInterceptor(tcpPingInterceptor);
        if (log.isDebugEnabled()) {
            log.debug("Added TCP Ping Interceptor");
        }

        // Add the NonBlockingCoordinator. This is used for leader election
        /*nbc = new NonBlockingCoordinator() {
            public void fireInterceptorEvent(InterceptorEvent event) {
                String status = event.getEventTypeDesc();
                System.err.println("$$$$$$$$$$$$ NBC status=" + status);
                int type = event.getEventType();
            }
        };
        nbc.setPrevious(dfi);
        channel.addInterceptor(nbc);*/

        // Add a reliable failure detector
        TcpFailureDetector tcpFailureDetector = new TcpFailureDetector();
//        tcpFailureDetector.setPrevious(dfi); //TODO: check this
//        tcpFailureDetector.setReadTestTimeout(30000);
        tcpFailureDetector.setConnectTimeout(30000);
        channel.addInterceptor(tcpFailureDetector);
        if (log.isDebugEnabled()) {
            log.debug("Added TCP Failure Detector");
        }

        staticMembershipInterceptor = new StaticMembershipInterceptor();
        staticMembershipInterceptor.setLocalMember(primaryMembershipManager.getLocalMember());
        primaryMembershipManager.setStaticMembershipInterceptor(staticMembershipInterceptor);
        channel.addInterceptor(staticMembershipInterceptor);
        if (log.isDebugEnabled()) {
            log.debug("Added Static Membership Interceptor");
        }

        channel.getMembershipService().setDomain(domain);
        mode.addInterceptors(channel);

        // Add a AtMostOnceInterceptor to support at-most-once message processing semantics
        AtMostOnceInterceptor atMostOnceInterceptor = new AtMostOnceInterceptor();
        atMostOnceInterceptor.setOptionFlag(TribesConstants.AT_MOST_ONCE_OPTION);
        channel.addInterceptor(atMostOnceInterceptor);
        if (log.isDebugEnabled()) {
            log.debug("Added At-most-once Interceptor");
        }

        // Add the OrderInterceptor to preserve sender ordering
        OrderInterceptor orderInterceptor = new OrderInterceptor();
        orderInterceptor.setOptionFlag(TribesConstants.MSG_ORDER_OPTION);
        channel.addInterceptor(orderInterceptor);
        if (log.isDebugEnabled()) {
            log.debug("Added Message Order Interceptor");
        }
    }

    /**
     * JOIN the group and get the member list
     *
     * @throws ClusteringFault If an error occurs while joining the group
     */
    public void joinGroup() throws ClusteringFault {

        // Have multiple RPC channels with multiple RPC request handlers for each domain
        // This is needed only when this member is running as a load balancer
        for (MembershipManager appDomainMembershipManager : applicationDomainMembershipManagers) {

            // Create an RpcChannel for each domain
            String domain = new String(appDomainMembershipManager.getDomain());
            new RpcChannel(domain.getBytes(),
                           channel,
                           new RpcRequestHandler(appDomainMembershipManager));
            if(log.isDebugEnabled()){
                log.debug("Created RPC Channel for application domain " + domain);
            }
        }

        // Send JOIN message to a WKA member
        if (primaryMembershipManager.getMembers().length > 0) {
            log.info("Sending JOIN message to WKA members...");
            org.apache.catalina.tribes.Member[] wkaMembers = primaryMembershipManager.getMembers(); // The well-known members
            try {
                Thread.sleep(3000); // Wait for sometime so that the WKA members can receive the MEMBER_LIST message, if they have just joined the group
            } catch (InterruptedException ignored) {
            }
            Response[] responses = null;
            do {
                try {
                    responses = rpcChannel.send(wkaMembers,
                                                new JoinGroupCommand(),
                                                RpcChannel.ALL_REPLY,
                                                Channel.SEND_OPTIONS_ASYNCHRONOUS |
                                                TribesConstants.MEMBERSHIP_MSG_OPTION,
                                                10000);
                    if (responses.length == 0) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ignored) {
                        }
                    }
                } catch (Exception e) {
                    String msg = "Error occurred while trying to send JOIN request to WKA members";
                    log.error(msg, e);
                    wkaMembers = primaryMembershipManager.getMembers();
                    if (wkaMembers.length == 0) {
                        log.warn("There are no well-known members");
                        break;
                    }
                }

                // TODO: If we do not get a response within some time, try to recover from this fault
            }
            while (responses == null || responses.length == 0);  // Wait until we've received at least one response

            //TODO: ######## If this node is a LB, it needs to get the entire domain to member-list map

            for (Response response : responses) {
                MemberListCommand command = (MemberListCommand) response.getMessage();
                command.setMembershipManager(primaryMembershipManager);
                command.execute(null); // Set the list of current members

                // If the WKA member is not part of this group, remove it
                if (!Arrays.equals(response.getSource().getDomain(),
                                   primaryMembershipManager.getLocalMember().getDomain())) {
                    primaryMembershipManager.memberDisappeared(response.getSource());
                    if(log.isDebugEnabled()){
                        log.debug("Removed member " + TribesUtil.getName(response.getSource()) + 
                                  " since it does not belong to the local domain " +
                                  new String(primaryMembershipManager.getLocalMember().getDomain()));
                    }
                }
            }

            // Send MEMBER_JOINE to the group
            if (primaryMembershipManager.getMembers().length > 0) {
                log.info("Sending MEMBER_JOINED to group...");
                MemberJoinedCommand memberJoinedCommand = new MemberJoinedCommand();
                memberJoinedCommand.setMember(primaryMembershipManager.getLocalMember());
                try {
                    rpcChannel.send(primaryMembershipManager.getMembers(),
                                    memberJoinedCommand,
                                    RpcChannel.ALL_REPLY,
                                    Channel.SEND_OPTIONS_ASYNCHRONOUS |
                                    TribesConstants.MEMBERSHIP_MSG_OPTION,
                                    10000);
                } catch (ChannelException e) {
                    String msg = "Could not send MEMBER_JOINED message to group";
                    log.error(msg, e);
                    throw new ClusteringFault(msg, e);
                }
            }
        }
    }

    public Parameter getParameter(String name) {
        return parameters.get(name);
    }

    private class RpcRequestHandler implements RpcCallback {

        private MembershipManager membershipManager;  //TODO: ############# Will need to inform about membership when a WKA member who is a LB joins

        private RpcRequestHandler(MembershipManager membershipManager) {
            this.membershipManager = membershipManager;
            membershipManager.setStaticMembershipInterceptor(staticMembershipInterceptor);
        }

        public Serializable replyRequest(Serializable msg, org.apache.catalina.tribes.Member sender) {
            String domain = new String(sender.getDomain());
            if(log.isDebugEnabled()){
                log.debug("Request received by RpcRequestHandler for domain " + domain);
            }
            if (msg instanceof JoinGroupCommand) {
                log.info("Received JOIN message from application member " +
                         TribesUtil.getName(sender) + " in domain " + domain);
                // Return the list of current members to the caller
                MemberListCommand memListCmd = new MemberListCommand();
                memListCmd.setMembers(membershipManager.getMembers());

                membershipManager.memberAdded(sender);
                return memListCmd;
            } else if (msg instanceof MemberJoinedCommand) {
                log.info("Received MEMBER_JOINED message from application member " +
                         TribesUtil.getName(sender) + " in domain " + domain);
                try {
                    MemberJoinedCommand command = (MemberJoinedCommand) msg;
                    command.setMembershipManager(membershipManager);
                    command.execute(null);
                } catch (ClusteringFault e) {
                    String errMsg = "Cannot handle MEMBER_JOINED notification";
                    log.error(errMsg, e);
                    throw new RemoteProcessException(errMsg, e);
                }
            } else if (msg instanceof MemberListCommand) {
                try {                    //TODO: What if we receive more than one member list message?
                    MemberListCommand command = (MemberListCommand) msg;
                    command.setMembershipManager(membershipManager);
                    command.execute(null);

                    //TODO Send MEMBER_JOINED messages to all nodes
                } catch (ClusteringFault e) {
                    String errMsg = "Cannot handle MEMBER_LIST message";
                    log.error(errMsg, e);
                    throw new RemoteProcessException(errMsg, e);
                }
            }
            return null;
        }

        public void leftOver(Serializable msg, org.apache.catalina.tribes.Member sender) {
        }
    }
}