/*
 * Copyright 2007-2017 the original author or authors.
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
package net.ymate.module.oauth.intercept;

import net.ymate.module.oauth.IOAuth;
import net.ymate.module.oauth.OAuth;
import net.ymate.platform.core.beans.intercept.IInterceptor;
import net.ymate.platform.core.beans.intercept.InterceptContext;
import net.ymate.platform.webmvc.context.WebContext;
import net.ymate.platform.webmvc.view.impl.HttpStatusView;
import org.apache.commons.lang.StringUtils;
import org.apache.oltu.oauth2.as.response.OAuthASResponse;
import org.apache.oltu.oauth2.common.error.OAuthError;
import org.apache.oltu.oauth2.common.exception.OAuthProblemException;
import org.apache.oltu.oauth2.common.message.OAuthResponse;
import org.apache.oltu.oauth2.common.message.types.ParameterStyle;
import org.apache.oltu.oauth2.rs.request.OAuthAccessResourceRequest;

import javax.servlet.http.HttpServletResponse;

/**
 * @author 刘镇 (suninformation@163.com) on 17/3/13 上午2:10
 * @version 1.0
 */
public class SnsAccessTokenCheckInterceptor implements IInterceptor {

    public Object intercept(InterceptContext context) throws Exception {
        switch (context.getDirection()) {
            case BEFORE:
                try {
                    OAuthAccessResourceRequest _oauthRequest = new OAuthAccessResourceRequest(WebContext.getRequest(), ParameterStyle.QUERY);
                    String _openId = WebContext.getRequest().getParameter(IOAuth.Const.OPEN_ID);
                    String _scope = context.getContextParams().get(org.apache.oltu.oauth2.common.OAuth.OAUTH_SCOPE);
                    OAuthResponse _response = null;
                    if (StringUtils.isBlank(_openId)) {
                        _response = OAuthASResponse.errorResponse(HttpServletResponse.SC_UNAUTHORIZED).setError(IOAuth.Const.INVALID_USER).buildJSONMessage();
                    } else {
                        IOAuth.IOAuthAccessResourceHelper _resourceHelper = OAuth.get().bindAccessResourceHelper(_oauthRequest.getAccessToken(), _openId);
                        if (!_resourceHelper.checkAccessToken()) {
                            _response = OAuthASResponse.errorResponse(HttpServletResponse.SC_UNAUTHORIZED).setError(OAuthError.ResourceResponse.INVALID_TOKEN).buildJSONMessage();
                        } else if (_resourceHelper.isExpiredAccessToken()) {
                            _response = OAuthASResponse.errorResponse(HttpServletResponse.SC_UNAUTHORIZED).setError(OAuthError.ResourceResponse.EXPIRED_TOKEN).buildJSONMessage();
                        } else if (StringUtils.isNotBlank(_scope) && !_resourceHelper.checkScope(_scope)) {
                            _response = OAuthASResponse.errorResponse(HttpServletResponse.SC_UNAUTHORIZED).setError(OAuthError.ResourceResponse.INSUFFICIENT_SCOPE).buildJSONMessage();
                        }
                    }
                    if (_response != null) {
                        return new HttpStatusView(_response.getResponseStatus(), false).writeBody(_response.getBody());
                    }
                } catch (OAuthProblemException e) {
                    OAuthResponse _response = OAuthASResponse
                            .errorResponse(HttpServletResponse.SC_BAD_REQUEST)
                            .error(e)
                            .buildJSONMessage();
                    return new HttpStatusView(_response.getResponseStatus(), false).writeBody(_response.getBody());
                }
        }
        return null;
    }
}
