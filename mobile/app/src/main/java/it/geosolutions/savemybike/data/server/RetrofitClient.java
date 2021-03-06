package it.geosolutions.savemybike.data.server;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.util.Log;

import com.amazonaws.mobileconnectors.cognitoidentityprovider.exceptions.CognitoParameterInvalidException;
import com.amazonaws.mobileconnectors.cognitoidentityprovider.tokens.CognitoIdToken;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

import it.geosolutions.savemybike.BuildConfig;
import it.geosolutions.savemybike.data.Constants;
import it.geosolutions.savemybike.model.Configuration;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Created by Robert Oehler on 07.12.17.
 *
 * A Retrofit client which connect to a AWS endpoint
 */

public class RetrofitClient {

    private static final String TAG = "RetrofitClient";

    private final static String ENDPOINT = "https://ex2rxvvhpc.execute-api.us-west-2.amazonaws.com/prod/";

    private Retrofit retrofit;
    private OkHttpClient client;

    private Context context;

    public RetrofitClient(final Context context) {
        this.context = context;
    }

    /**
     * get the config from the server
     * checks if a valid server token is available and uses it to fetch the current config
     * otherwise or if no token is available acquires a new token
     *
     * @param callback callback for the result
     */
    public void getRemoteConfig(@NonNull final GetConfigCallback callback) {

        String idTokenString = getSavedTokenString(context);

        // Requests will fail if we use the AccessToken
        if(idTokenString == null){
            Log.d(TAG, "Token not available, need to login first");
            return;
        }

        CognitoIdToken accessToken = new CognitoIdToken(idTokenString);
        try {
            if (System.currentTimeMillis() < accessToken.getExpiration().getTime()) {

                fetchConfig(callback);

            } else {

                Log.d(TAG, "Token Expired");
            /*
            acquireToken(new Authenticate() {
                @Override
                public void success() {
                    fetchConfig(preferences.getString(Constants.PREF_CONFIG_IDTOKEN, null), callback);
                }

                @Override
                public void error(String message) {
                    Log.e(TAG, message);
                }
            });
            */
            }
        }catch (CognitoParameterInvalidException cpie){
            Log.e(TAG, "Invalid Token found in preferences");
        }
    }


    public static String getSavedTokenString(Context context){
        //do we have a valid token ?
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        return preferences.getString(Constants.PREF_CONFIG_IDTOKEN, null);
    }


    /**
     * fetches the current configuration from the AWS server using @param token for authentication
     * @param callback call for the result
     */
    private void fetchConfig(@NonNull final GetConfigCallback callback){

        //do the (retrofit) get call
        final Call<Configuration> call = getServices().getConfig();

        try {
            final Configuration configuration = call.execute().body();

            callback.gotConfig(configuration);

        } catch (IOException e) {
            Log.e(TAG, "error executing getConfig", e);
            callback.error("io-error executing getConfig");
        }
    }

    /*
     * acquires a token by accessing the AWS user pool and logging in
     *
     * the token is then saved to local prefs for future user
     * and passed to fetch the actual config
     *
     * @see "http://docs.aws.amazon.com/cognito/latest/developerguide/tutorial-integrating-user-pools-android.html#tutorial-integrating-user-pools-user-sign-in-android"
     *
     * @param callback for the result of the operation
     */
    /*
    private void acquireToken(@NonNull final Authenticate callback){

        // Create a CognitoUserPool object to refer to your user pool
        final CognitoUserPool userPool = new CognitoUserPool(context, Constants.AWS_POOL, Constants.AWS_CLIENT_ID_WO_SECRET,null, Regions.US_WEST_2);

        final CognitoUser currentUser = userPool.getCurrentUser();

        // Callback handler for the sign-in process
        final AuthenticationHandler authenticationHandler = new AuthenticationHandler() {

            @Override
            public void onSuccess(CognitoUserSession userSession, CognitoDevice newDevice) {

                // Get id token from CognitoUserSession.
                String accessToken = userSession.getAccessToken().getJWTToken();
                String idToken = userSession.getIdToken().getJWTToken();
                String refreshToken = userSession.getRefreshToken().getToken();

                Log.d(TAG, accessToken);
                Log.d(TAG, idToken);
                Log.d(TAG, refreshToken);

                SharedPreferences.Editor ed = PreferenceManager.getDefaultSharedPreferences(context).edit();
                ed.putString(Constants.PREF_CONFIG_ACCESSTOKEN, accessToken);
                ed.putString(Constants.PREF_CONFIG_IDTOKEN, idToken);
                ed.putString(Constants.PREF_CONFIG_REFRESHTOKEN, refreshToken);
                ed.apply();

                callback.success();
            }

            @Override
            public void getAuthenticationDetails(AuthenticationContinuation authenticationContinuation, String userId) {

                // The API needs user sign-in credentials to continue
                final AuthenticationDetails authenticationDetails = new AuthenticationDetails(Constants.AWS_USER, Constants.AWS_PASS, null);
                // Pass the user sign-in credentials to the continuation
                authenticationContinuation.setAuthenticationDetails(authenticationDetails);
                // Allow the sign-in to continue
                authenticationContinuation.continueTask();
            }

            @Override
            public void getMFACode(MultiFactorAuthenticationContinuation multiFactorAuthenticationContinuation) {

                callback.error("Multi-factor authentication is required");
            }

            @Override
            public void authenticationChallenge(ChallengeContinuation continuation) {

                callback.error("authenticationChallenge");
            }

            @Override
            public void onFailure(Exception exception) {
                // Sign-in failed, check exception for the cause
                callback.error("sign-in failed " + exception.getMessage());
            }
        };

        if (currentUser != null) {
            Log.i(TAG, "requesting session");
            // get the current session
            currentUser.getSession(authenticationHandler);
        }else{
            callback.error("No current user available");
        }
    }
    */


    private Retrofit getRetrofit(){
        if(retrofit == null){
            retrofit = new Retrofit.Builder()
                    .client(getClient())
                    .baseUrl(ENDPOINT)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();

        }
        return retrofit;
    }

    private OkHttpClient getClient(){

        if(client == null) {
            client  = new OkHttpClient.Builder()
                    .addInterceptor(new TokenInterceptor(context))
                    .addInterceptor(new LoggingInterceptor())
                    .build();
        }
        return client;
    }

    /**
     * used to authenticate
     */
    private static class TokenInterceptor implements Interceptor {

        private String token;

        TokenInterceptor(String token) {
            this.token = token;
        }

        TokenInterceptor(Context context){

            this.token = getSavedTokenString(context);

        }

        @Override
        public Response intercept(Chain chain) throws IOException {

            // TODO: inject authentication token

            Request request = chain.request();
            Request authenticatedRequest = request.newBuilder().header("Authorization", token).build();
            return chain.proceed(authenticatedRequest);
        }
    }

    /**
     * used to log communication
     */
    private static class LoggingInterceptor implements Interceptor {

        @Override public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            long t1 = System.nanoTime();
            String requestLog = String.format(Locale.US, "Sending request %s on %s%n%s",request.url(), chain.connection(), request.headers());

            if(request.method().compareToIgnoreCase("post")==0){
                requestLog ="\n"+requestLog+"\n"+bodyToString(request);
            }
            if(BuildConfig.DEBUG) {
                Log.d(TAG, "request" + "\n" + requestLog);
            }
            Response response = chain.proceed(request);
            long t2 = System.nanoTime();

            String responseLog = String.format(Locale.US, "Received response for %s in %.1fms%n%s", response.request().url(), (t2 - t1) / 1e6d, response.headers());
            String bodyString = response.body().string();

            if(BuildConfig.DEBUG) {
                Log.d(TAG, "response" + "\n" + responseLog + "\n" + bodyString);
            }

            return response.newBuilder()
                    .body(ResponseBody.create(response.body().contentType(), bodyString))
                    .build();
        }
    }

    private static String bodyToString(final Request request) {
        try {
            final Request copy = request.newBuilder().build();
            final Buffer buffer = new Buffer();
            copy.body().writeTo(buffer);
            return buffer.readUtf8();
        } catch (final IOException e) {
            Log.e(TAG,"error bodyToString", e);
            return "error";
        }
    }

    private SMBRemoteServices getServices(){

        return getRetrofit().create(SMBRemoteServices.class);

    }

    public interface GetConfigCallback
    {
        void gotConfig(Configuration configuration);
        void error(String message);
    }
/*
    public interface Authenticate
    {
        void success();
        void error(String message);
    }
*/

    public void uploadFile(String s3ObjectKey, File file, Callback callback) {

        // TODO make a singleton for the services
        // create upload service client
        SMBRemoteServices service = getServices();

        // create RequestBody instance from file
        RequestBody requestFile =
                RequestBody.create(
                        MediaType.parse("application/zip"),
                        file
                );

        // finally, execute the request
        Call<ResponseBody> call = service.upload(s3ObjectKey, requestFile);
        call.enqueue(callback);
    }

}
