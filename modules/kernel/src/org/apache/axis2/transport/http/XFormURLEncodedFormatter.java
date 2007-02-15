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
package org.apache.axis2.transport.http;

import org.apache.axis2.transport.MessageFormatter;
import org.apache.axis2.transport.http.util.URLTemplatingUtil;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.axis2.description.WSDL2Constants;
import org.apache.axis2.util.JavaUtils;
import org.apache.axiom.om.OMOutputFormat;
import org.apache.axiom.om.OMElement;

import java.io.OutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.MalformedURLException;
import java.util.Iterator;

/**
 * Formates the request message as application/x-www-form-urlencoded
 */
public class XFormURLEncodedFormatter implements MessageFormatter {

    public byte[] getBytes(MessageContext messageContext, OMOutputFormat format) throws AxisFault {

        OMElement omElement = messageContext.getEnvelope().getBody().getFirstElement();

        if (omElement != null) {
            Iterator it = omElement.getChildElements();
            String paraString = "";

            while (it.hasNext()) {
                OMElement ele1 = (OMElement) it.next();
                String parameter;

                parameter = ele1.getLocalName() + "=" + ele1.getText();
                paraString = "".equals(paraString) ? parameter : (paraString + "&" + parameter);
            }

            return paraString.getBytes();
        }

        return new byte[0];
    }

    public void writeTo(MessageContext messageContext, OMOutputFormat format,
                        OutputStream outputStream, boolean preserve) throws AxisFault {

        try {
            outputStream.write(getBytes(messageContext, format));
        } catch (IOException e) {
            throw new AxisFault("An error occured while writing the request");
        }
    }

    public String getContentType(MessageContext messageContext, OMOutputFormat format,
                                 String soapAction) {

        String encoding = format.getCharSetEncoding();
        String contentType = HTTPConstants.MEDIA_TYPE_X_WWW_FORM;

        if (encoding != null) {
            contentType += "; charset=" + encoding;
        }

        // if soap action is there (can be there is soap response MEP is used) add it.
        if ((soapAction != null)
                && !"".equals(soapAction.trim())
                && !"\"\"".equals(soapAction.trim())) {
            contentType = contentType + ";action=\"" + soapAction + "\";";
        }

        return contentType;
    }

    public URL getTargetAddress(MessageContext messageContext, OMOutputFormat format, URL targetURL)
            throws AxisFault {

        // Check whether there is a template in the URL, if so we have to replace then with data
        // values and create a new target URL.
        targetURL = URLTemplatingUtil.getTemplatedURL(targetURL, messageContext, true);
        targetURL = URLTemplatingUtil.appendParametersToURL(messageContext, targetURL);

        return targetURL;
    }

    public String formatSOAPAction(MessageContext messageContext, OMOutputFormat format,
                                   String soapAction) {
        return soapAction;
    }
}
