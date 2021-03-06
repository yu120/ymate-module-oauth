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
package net.ymate.module.oauth.controller;

import net.ymate.framework.core.Optional;
import net.ymate.framework.core.support.TokenProcessHelper;
import net.ymate.framework.core.util.WebUtils;
import net.ymate.framework.webmvc.intercept.UserSessionCheckInterceptor;
import net.ymate.framework.webmvc.support.UserSessionBean;
import net.ymate.module.oauth.IOAuth;
import net.ymate.module.oauth.OAuth;
import net.ymate.module.oauth.OAuthCode;
import net.ymate.module.oauth.OAuthSnsToken;
import net.ymate.module.oauth.intercept.SnsAccessTokenCheckInterceptor;
import net.ymate.platform.core.beans.annotation.Before;
import net.ymate.platform.core.beans.annotation.ContextParam;
import net.ymate.platform.core.beans.annotation.ParamItem;
import net.ymate.platform.webmvc.annotation.Controller;
import net.ymate.platform.webmvc.annotation.RequestMapping;
import net.ymate.platform.webmvc.annotation.RequestParam;
import net.ymate.platform.webmvc.base.Type;
import net.ymate.platform.webmvc.context.WebContext;
import net.ymate.platform.webmvc.view.IView;
import net.ymate.platform.webmvc.view.View;
import net.ymate.platform.webmvc.view.impl.HttpStatusView;
import org.apache.commons.lang.StringUtils;
import org.apache.oltu.oauth2.as.request.OAuthAuthzRequest;
import org.apache.oltu.oauth2.as.request.OAuthTokenRequest;
import org.apache.oltu.oauth2.as.response.OAuthASResponse;
import org.apache.oltu.oauth2.common.error.OAuthError;
import org.apache.oltu.oauth2.common.exception.OAuthProblemException;
import org.apache.oltu.oauth2.common.exception.OAuthSystemException;
import org.apache.oltu.oauth2.common.message.OAuthResponse;
import org.apache.oltu.oauth2.common.message.types.GrantType;
import org.apache.oltu.oauth2.common.message.types.ResponseType;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * @author 刘镇 (suninformation@163.com) on 17/3/3 上午1:43
 * @version 1.0
 */
@Controller
@RequestMapping("/oauth2")
public class OAuthController {

    private OAuthResponse __doTokenToResponse(OAuthSnsToken token) throws OAuthSystemException {
        return OAuthASResponse.tokenResponse(HttpServletResponse.SC_OK)
                .setAccessToken(token.getAccessToken())
                .setExpiresIn(String.valueOf(OAuth.get().getModuleCfg().getAccessTokenExpireIn()))
                .setRefreshToken(token.getRefreshToken())
                .setScope(token.getScope())
                .setParam(IOAuth.Const.OPEN_ID, token.getOpenId())
                .buildJSONMessage();
    }

    private OAuthResponse __responseBadRequest(String error) throws OAuthSystemException {
        return OAuthASResponse
                .errorResponse(HttpServletResponse.SC_BAD_REQUEST)
                .setError(error)
                .buildJSONMessage();
    }

    private OAuthResponse __toUnauthorizedClient() throws OAuthSystemException {
        return OAuthASResponse
                .errorResponse(HttpServletResponse.SC_UNAUTHORIZED)
                .setError(OAuthError.TokenResponse.UNAUTHORIZED_CLIENT)
                .buildJSONMessage();
    }

    private OAuthResponse __toBadRequestError(OAuthProblemException e) throws OAuthSystemException {
        return OAuthASResponse
                .errorResponse(HttpServletResponse.SC_BAD_REQUEST)
                .error(e)
                .buildJSONMessage();
    }

    /**
     * @return 返回访问凭证 (grant_type=client_credentials)
     * @throws Exception 可能产生的任何异常
     */
    @RequestMapping(value = "/token", method = Type.HttpMethod.POST)
    public IView token() throws Exception {
        OAuthResponse _response = null;
        try {
            OAuthTokenRequest _oauthRequest = new OAuthTokenRequest(WebContext.getRequest());
            GrantType _grantType = GrantType.valueOf(StringUtils.upperCase(_oauthRequest.getParam(org.apache.oltu.oauth2.common.OAuth.OAUTH_GRANT_TYPE)));
            IOAuth.IOAuthClientHelper _clientHelper = OAuth.get().bindClientHelper(_oauthRequest.getClientId(), _oauthRequest.getClientSecret());
            if (!_clientHelper.checkClientId()) {
                _response = __responseBadRequest(OAuthError.TokenResponse.INVALID_CLIENT);
            } else if (!_clientHelper.checkClientSecret()) {
                _response = __toUnauthorizedClient();
            } else if (GrantType.CLIENT_CREDENTIALS.equals(_grantType)) {
                _response = OAuthASResponse.tokenResponse(HttpServletResponse.SC_OK)
                        .setAccessToken(_clientHelper.createOrUpdateAccessToken().getAccessToken())
                        .setExpiresIn(String.valueOf(OAuth.get().getModuleCfg().getAccessTokenExpireIn()))
                        .buildJSONMessage();
            } else {
                _response = __responseBadRequest(OAuthError.TokenResponse.UNSUPPORTED_GRANT_TYPE);
            }
        } catch (OAuthProblemException e) {
            _response = __toBadRequestError(e);
        } catch (IllegalArgumentException e) {
            _response = __responseBadRequest(OAuthError.TokenResponse.INVALID_GRANT);
        }
        return new HttpStatusView(_response.getResponseStatus(), false).writeBody(_response.getBody());
    }

    /**
     * 获取CODE授权码 (response_type=code, scope=[snsapi_base|snsapi_userinfo])
     *
     * @return 重定向至redirect_url指定的URL地址
     * @throws Exception 可能产生的任何异常
     */
    @RequestMapping(value = "/sns/authorize", method = {Type.HttpMethod.POST, Type.HttpMethod.GET})
    @Before(UserSessionCheckInterceptor.class)
    @ContextParam(@ParamItem(Optional.OBSERVE_SILENCE))
    public IView authorize(@RequestParam(defaultValue = "false") Boolean authorized) throws Exception {
        OAuthResponse _response = null;
        try {
            HttpServletRequest _request = WebContext.getRequest();
            OAuthAuthzRequest _oauthRequest = new OAuthAuthzRequest(_request);
            ResponseType _responseType = ResponseType.valueOf(StringUtils.upperCase(_oauthRequest.getParam(org.apache.oltu.oauth2.common.OAuth.OAUTH_RESPONSE_TYPE)));
            String _redirectURI = _oauthRequest.getParam(org.apache.oltu.oauth2.common.OAuth.OAUTH_REDIRECT_URI);
            if (StringUtils.isBlank(_redirectURI)) {
                _response = __responseBadRequest(IOAuth.Const.INVALID_REDIRECT_URI);
            } else {
                String _uid = UserSessionBean.current().getUid();
                IOAuth.IOAuthAuthzHelper _authzHelper = OAuth.get().bindAuthzHelper(_oauthRequest.getClientId(), _uid);
                if (!_authzHelper.checkClientId()) {
                    _response = __responseBadRequest(OAuthError.TokenResponse.INVALID_CLIENT);
                } else {
                    String _scope = _oauthRequest.getParam(org.apache.oltu.oauth2.common.OAuth.OAUTH_SCOPE);
                    if (!IOAuth.Scope.verified(_scope)) {
                        _response = __responseBadRequest(OAuthError.CodeResponse.INVALID_SCOPE);
                    } else if (ResponseType.CODE.equals(_responseType)) {
                        String _state = _oauthRequest.getParam(org.apache.oltu.oauth2.common.OAuth.OAUTH_STATE);
                        if (WebUtils.isPost(_request)) {
                            if (TokenProcessHelper.getInstance().isTokenValid(_request, true)) {
                                if (!authorized) {
                                    _response = OAuthASResponse.authorizationResponse(_request, HttpServletResponse.SC_FOUND)
                                            .location(_redirectURI)
                                            .setParam(org.apache.oltu.oauth2.common.OAuth.OAUTH_STATE, _state)
                                            .buildQueryMessage();
                                } else {
                                    OAuthCode _authzCode = _authzHelper.createOrUpdateAuthCode(_redirectURI, _scope);
                                    //
                                    _response = OAuthASResponse.authorizationResponse(_request, HttpServletResponse.SC_FOUND)
                                            .location(_redirectURI)
                                            .setCode(_authzCode.getCode())
                                            .setParam(org.apache.oltu.oauth2.common.OAuth.OAUTH_STATE, _state)
                                            .buildQueryMessage();
                                }
                            } else {
                                _response = __responseBadRequest(OAuthError.TokenResponse.INVALID_REQUEST);
                                return new HttpStatusView(_response.getResponseStatus(), false).writeBody(_response.getBody());
                            }
                        } else {
                            if (IOAuth.Scope.SNSAPI_USERINFO.equalsIgnoreCase(_scope) && _authzHelper.checkUserNeedAuth()) {
                                // 若需要用户授权则跳转
                                return View.jspView(OAuth.get().getModuleCfg().getAuthorizationView())
                                        .addAttribute("client_title", _authzHelper.getOAuthClient().getTitle())
                                        .addAttribute("client_icon", _authzHelper.getOAuthClient().getIconUrl())
                                        .addAttribute("client_domain", _authzHelper.getOAuthClient().getDomain());
                            } else {
                                OAuthCode _authzCode = _authzHelper.createOrUpdateAuthCode(_redirectURI, _scope);
                                //
                                _response = OAuthASResponse.authorizationResponse(_request, HttpServletResponse.SC_FOUND)
                                        .location(_redirectURI)
                                        .setCode(_authzCode.getCode())
                                        .setParam(org.apache.oltu.oauth2.common.OAuth.OAUTH_STATE, _state)
                                        .buildQueryMessage();
                            }
                        }
                        return View.httpStatusView(_response.getResponseStatus()).addHeader("Location", _response.getLocationUri());
                    } else {
                        _response = __responseBadRequest(OAuthError.CodeResponse.UNSUPPORTED_RESPONSE_TYPE);
                    }
                }
            }
        } catch (OAuthProblemException e) {
            _response = __toBadRequestError(e);
        } catch (IllegalArgumentException e) {
            _response = __responseBadRequest(OAuthError.CodeResponse.UNSUPPORTED_RESPONSE_TYPE);
        }
        WebContext.getResponse().setStatus(_response.getResponseStatus());
        return WebUtils.buildErrorView(WebContext.getContext().getOwner(), 0, _response.getBody());
    }

    /**
     * @return 返回访问凭证 (grant_type=[authorization_code|password])
     * @throws Exception 可能产生的任何异常
     */
    @RequestMapping(value = "/sns/access_token", method = Type.HttpMethod.POST)
    public IView accessToken() throws Exception {
        OAuthResponse _response = null;
        try {
            OAuthTokenRequest _oauthRequest = new OAuthTokenRequest(WebContext.getRequest());
            GrantType _grantType = GrantType.valueOf(StringUtils.upperCase(_oauthRequest.getParam(org.apache.oltu.oauth2.common.OAuth.OAUTH_GRANT_TYPE)));
            if (GrantType.AUTHORIZATION_CODE.equals(_grantType)) {
                IOAuth.IOAuthTokenHelper _tokenHelper = OAuth.get().bindTokenHelper(_oauthRequest.getClientId(), _oauthRequest.getClientSecret(), _oauthRequest.getParam(org.apache.oltu.oauth2.common.OAuth.OAUTH_CODE));
                if (!_tokenHelper.checkClientId()) {
                    _response = __responseBadRequest(OAuthError.TokenResponse.INVALID_CLIENT);
                } else if (!_tokenHelper.checkClientSecret()) {
                    _response = __toUnauthorizedClient();
                } else if (!_tokenHelper.checkAuthCode()) {
                    _response = __responseBadRequest(OAuthError.TokenResponse.INVALID_GRANT);
                } else if (!StringUtils.equals(_oauthRequest.getRedirectURI(), _tokenHelper.getOAuthCode().getRedirectUri())) {
                    _response = __responseBadRequest(IOAuth.Const.REDIRECT_URI_MISMATCH);
                } else {
                    _response = __doTokenToResponse(_tokenHelper.createOrUpdateAccessToken());
                }
            } else if (GrantType.PASSWORD.equals(_grantType)) {
                String _scope = _oauthRequest.getParam(org.apache.oltu.oauth2.common.OAuth.OAUTH_SCOPE);
                if (!IOAuth.Scope.verified(_scope)) {
                    _response = __responseBadRequest(OAuthError.CodeResponse.INVALID_SCOPE);
                } else {
                    IOAuth.IOAuthTokenHelper _tokenHelper = OAuth.get().bindTokenHelper(_oauthRequest.getClientId(), _oauthRequest.getClientSecret(), _scope, _oauthRequest.getUsername(), _oauthRequest.getPassword());
                    if (!_tokenHelper.checkClientId()) {
                        _response = __responseBadRequest(OAuthError.TokenResponse.INVALID_CLIENT);
                    } else if (!_tokenHelper.checkClientSecret()) {
                        _response = __toUnauthorizedClient();
                    } else if (!_tokenHelper.checkAuthUser()) {
                        _response = __responseBadRequest(IOAuth.Const.INVALID_USER);
                    } else {
                        _response = __doTokenToResponse(_tokenHelper.createOrUpdateAccessToken());
                    }
                }
            } else {
                _response = __responseBadRequest(OAuthError.TokenResponse.UNSUPPORTED_GRANT_TYPE);
            }
        } catch (OAuthProblemException e) {
            _response = __toBadRequestError(e);
        } catch (IllegalArgumentException e) {
            _response = __responseBadRequest(OAuthError.TokenResponse.INVALID_GRANT);
        }
        return new HttpStatusView(_response.getResponseStatus(), false).writeBody(_response.getBody());
    }

    /**
     * @return 刷新访问凭证 (grant_type=refresh_token)
     * @throws Exception 可能产生的任何异常
     */
    @RequestMapping(value = "/sns/refresh_token", method = Type.HttpMethod.POST)
    public IView refreshToken() throws Exception {
        OAuthResponse _response = null;
        try {
            OAuthTokenRequest _oauthRequest = new OAuthTokenRequest(WebContext.getRequest());
            GrantType _grantType = GrantType.valueOf(StringUtils.upperCase(_oauthRequest.getParam(org.apache.oltu.oauth2.common.OAuth.OAUTH_GRANT_TYPE)));
            IOAuth.IOAuthTokenHelper _tokenHelper = OAuth.get().bindTokenHelper(_oauthRequest.getClientId(), _oauthRequest.getParam(org.apache.oltu.oauth2.common.OAuth.OAUTH_REFRESH_TOKEN));
            if (!_tokenHelper.checkClientId()) {
                _response = __responseBadRequest(OAuthError.TokenResponse.INVALID_CLIENT);
            } else if (GrantType.REFRESH_TOKEN.equals(_grantType)) {
                if (!_tokenHelper.checkRefreshToken()) {
                    _response = OAuthASResponse
                            .errorResponse(HttpServletResponse.SC_BAD_REQUEST)
                            .setError(OAuthError.ResourceResponse.INVALID_TOKEN)
                            .buildJSONMessage();
                } else if (_tokenHelper.isExpiredRefreshToken()) {
                    _response = OAuthASResponse
                            .errorResponse(HttpServletResponse.SC_BAD_REQUEST)
                            .setError(OAuthError.ResourceResponse.EXPIRED_TOKEN)
                            .buildJSONMessage();
                } else {
                    _response = __doTokenToResponse(_tokenHelper.refreshAccessToken());
                }
            } else {
                _response = __responseBadRequest(OAuthError.TokenResponse.UNSUPPORTED_GRANT_TYPE);
            }
        } catch (OAuthProblemException e) {
            _response = __toBadRequestError(e);
        } catch (IllegalArgumentException e) {
            _response = __responseBadRequest(OAuthError.TokenResponse.INVALID_GRANT);
        }
        return new HttpStatusView(_response.getResponseStatus(), false).writeBody(_response.getBody());
    }

    /**
     * @return 验证访问凭证是否有效
     * @throws Exception 可能产生的任何异常
     */
    @RequestMapping("/sns/auth")
    @Before(SnsAccessTokenCheckInterceptor.class)
    public IView auth() throws Exception {
        OAuthResponse _response = OAuthASResponse.errorResponse(HttpServletResponse.SC_OK).setError("ok").buildJSONMessage();
        return new HttpStatusView(_response.getResponseStatus(), false).writeBody(_response.getBody());
    }

    /**
     * @param accountToken 网页授权接口调用凭证
     * @param openId       用户的唯一标识
     * @return 返回用户信息 (OAuth2授权需scope=snsapi_userinfo)
     * @throws Exception 可能产生的任何异常
     */
    @RequestMapping("/sns/userinfo")
    @Before(SnsAccessTokenCheckInterceptor.class)
    @ContextParam(@ParamItem(key = IOAuth.Const.SCOPE, value = IOAuth.Scope.SNSAPI_USERINFO))
    public IView userinfo(@RequestParam(IOAuth.Const.ACCESS_TOKEN) String accountToken, @RequestParam(IOAuth.Const.OPEN_ID) String openId) throws Exception {
        try {
            return View.jsonView(OAuth.get().getModuleCfg().getUserInfoAdapter().getUserInfo(OAuth.get().bindAccessResourceHelper(accountToken, openId).getOAuthClientUser().getUid()));
        } catch (Exception e) {
            OAuthResponse _response = __responseBadRequest(IOAuth.Const.INVALID_USER);
            return new HttpStatusView(_response.getResponseStatus(), false).writeBody(_response.getBody());
        }
    }
}
