/*
 * substitution-schedule-parser - Java library for parsing schools' substitution schedules
 * Copyright (c) 2016 Johan v. Forstner
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package me.vertretungsplan.parser;

import me.vertretungsplan.exception.CredentialInvalidException;
import me.vertretungsplan.objects.SubstitutionSchedule;
import me.vertretungsplan.objects.SubstitutionScheduleData;
import me.vertretungsplan.objects.credential.UserPasswordCredential;
import org.apache.http.NameValuePair;
import org.apache.http.message.BasicNameValuePair;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for Untis substitution schedules served by
 * <a href="http://www.digitales-schwarzes-brett.de/">DSB</a>light. Supports both password-protected and public
 * schedules.
 * <p>
 * It seems that the "light" version of DSB is discontinued, many schools are currently switching to the newer
 * DSBmobile (which can be parsed with {@link DSBMobileParser}.
 * <p>
 * This parser can be accessed using <code>"dsblight"</code> for {@link SubstitutionScheduleData#setApi(String)}.
 *
 * <h4>Configuration parameters</h4>
 * These parameters can be supplied in {@link SubstitutionScheduleData#setData(JSONObject)} to configure the parser:
 *
 * <dl>
 * <dt><code>id</code> (String, required)</dt>
 * <dd>The ID of the DSBlight instance. This is a
 * <a href="https://en.wikipedia.org/wiki/Universally_unique_identifier">UUID</a> and can be found in the URL
 * (<code>Player.aspx?ID=...</code>)</dd>
 *
 * <dt><code>classes</code> (Array of Strings, required)</dt>
 * <dd>The list of all classes, as they can appear in the schedule</dd>
 *
 * <dt><code>encoding</code> (String, required)</dt>
 * <dd>The charset of the Untis schedule. DSBlight itself always uses UTF-8, but the hosted HTML schedule can
 * also be ISO-8859-1.</dd>
 *
 * <dt><code>login</code> (Boolean, optional)</dt>
 * <dd>Whether this DSBlight instance requires login using a username and a password. Default: <code>false</code></dd>
 * </dl>
 *
 * Additionally, this parser supports the parameters specified in {@link UntisCommonParser}.
 *
 * For password protected schedules, you have to use a
 * {@link me.vertretungsplan.objects.authentication.UserPasswordAuthenticationData}.
 */
public class DSBLightParser extends UntisCommonParser {

    private static final String BASE_URL = "https://light.dsbcontrol.de/DSBlightWebsite/Homepage/";
    private static final String ENCODING = "UTF-8";
    private static final String PARAM_ID = "id";
    private static final String PARAM_LOGIN = "login";
    private static final String PARAM_CLASSES = "classes";
    private static final String PARAM_ENCODING = "encoding";

    private JSONObject data;

    public DSBLightParser(SubstitutionScheduleData scheduleData, CookieProvider cookieProvider) {
        super(scheduleData, cookieProvider);
        data = scheduleData.getData();
    }


    @Override
    public SubstitutionSchedule getSubstitutionSchedule() throws IOException,
            JSONException, CredentialInvalidException {
        String id = data.getString(PARAM_ID);
        SubstitutionSchedule v = SubstitutionSchedule.fromData(scheduleData);

        Map<String, String> referer = new HashMap<>();
        referer.put("Referer", BASE_URL + "/Player.aspx?ID=" + id);

        String response = httpGet(BASE_URL + "/Player.aspx?ID=" + id, ENCODING, referer);
        Document doc = Jsoup.parse(response);
        // IFrame.aspx
        String iframeUrl = doc.select("iframe").first().attr("src");

        response = httpGet(iframeUrl, ENCODING, referer);

        doc = Jsoup.parse(response);

        if (data.has(PARAM_LOGIN) && data.getBoolean(PARAM_LOGIN)) {
            if (!(credential instanceof UserPasswordCredential)) {
                throw new IllegalArgumentException("no login");
            }
            String username = ((UserPasswordCredential) credential).getUsername();
            String password = ((UserPasswordCredential) credential).getPassword();

            List<NameValuePair> params = new ArrayList<>();
            params.add(new BasicNameValuePair("__VIEWSTATE", doc.select(
                    "#__VIEWSTATE").attr("value")));
            params.add(new BasicNameValuePair("__VIEWSTATEGENERATOR", doc.select(
                    "#__VIEWSTATEGENERATOR").attr("value")));
            params.add(new BasicNameValuePair("__EVENTVALIDATION", doc.select(
                    "#__EVENTVALIDATION").attr("value")));
            params.add(new BasicNameValuePair("ctl02$txtBenutzername", username));
            params.add(new BasicNameValuePair("ctl02$txtPasswort", password));
            params.add(new BasicNameValuePair("ctl02$btnLogin", "weiter"));
            response = httpPost(iframeUrl, ENCODING, params, referer);
            doc = Jsoup.parse(response);
            if (doc.select("#ctl02_lblLoginFehlgeschlagen").size() > 0) throw new CredentialInvalidException();
        }
        Pattern regex = Pattern.compile("location\\.href=\"([^\"]*)\"");

        for (Element iframe : doc.select("iframe")) {
            // PreProgram.aspx
            String response2 = httpGet(iframe.attr("src"), ENCODING, referer);
            Matcher matcher = regex.matcher(response2);
            if (matcher.find()) {
                // Program.aspx
                String url = matcher.group(1);
                parseProgram(url, v, referer);
            } else {
                throw new IOException("URL nicht gefunden");
            }
        }

        v.setClasses(getAllClasses());
        v.setTeachers(getAllTeachers());
        v.setWebsite(BASE_URL + "/Player.aspx?ID=" + id);

        return v;
    }

    private void parseProgram(String url, SubstitutionSchedule schedule, Map<String, String> referer) throws
            IOException, JSONException {
        parseProgram(url, schedule, referer, null);
    }

    private void parseProgram(String url, SubstitutionSchedule schedule, Map<String, String> referer, String
            firstUrl) throws IOException, JSONException {
        String response = httpGet(url, ENCODING, referer);
        Document doc = Jsoup.parse(response, url);
        if (doc.select("iframe").attr("src").equals(firstUrl)) {
            return;
        }
        for (Element iframe : doc.select("iframe")) {
            // Data
            parseDay(iframe.attr("src"), referer, schedule, iframe.attr("src"));
        }
        if (doc.select("#hlNext").size() > 0) {
            String nextUrl = doc.select("#hlNext").first().attr("abs:href");
            if (firstUrl == null) {
                parseProgram(nextUrl, schedule, referer, doc.select("iframe").attr("src"));
            } else {
                parseProgram(nextUrl, schedule, referer, firstUrl);
            }
        }
    }

    private void parseDay(String url, Map<String, String> referer, SubstitutionSchedule schedule, String startUrl)
            throws IOException, JSONException {
        String html = httpGet(url, data.getString(PARAM_ENCODING), referer);
        Document doc = Jsoup.parse(html);
        if (doc.title().toLowerCase().contains("untis")
                || doc.html().toLowerCase().contains("untis") || doc.select(".mon_list").size() > 0) {
            schedule.addDay(parseMonitorDay(doc, data));
            if (doc.select("meta[http-equiv=refresh]").size() > 0) {
                Element meta = doc.select("meta[http-equiv=refresh]").first();
                String attr = meta.attr("content").toLowerCase();
                String redirectUrl = url.substring(0, url.lastIndexOf("/") + 1) +
                        attr.substring(attr.indexOf("url=") + 4);
                if (!redirectUrl.equals(startUrl)) {
                    parseDay(redirectUrl, referer, schedule, startUrl);
                }
            }
        }
    }

    @Override
    public List<String> getAllClasses() throws IOException, JSONException {
        JSONArray classesJson = data.getJSONArray(PARAM_CLASSES);
        List<String> classes = new ArrayList<>();
        for (int i = 0; i < classesJson.length(); i++) {
            classes.add(classesJson.getString(i));
        }
        return classes;
    }

    @Override
    public List<String> getAllTeachers() {
        return null;
    }

}
