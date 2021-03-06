/*
 * Copyright 2014 Uwe Trottmann
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

package com.battlelancer.seriesguide.util;

import android.content.ContentProviderOperation;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import com.battlelancer.seriesguide.items.MovieDetails;
import com.battlelancer.seriesguide.settings.DisplaySettings;
import com.battlelancer.seriesguide.settings.TraktCredentials;
import com.battlelancer.seriesguide.settings.TraktSettings;
import com.jakewharton.trakt.Trakt;
import com.jakewharton.trakt.entities.Movie;
import com.jakewharton.trakt.enumerations.Extended;
import com.jakewharton.trakt.services.MovieService;
import com.jakewharton.trakt.services.UserService;
import com.uwetrottmann.androidutils.AndroidUtils;
import com.uwetrottmann.tmdb.services.MoviesService;
import de.greenrobot.event.EventBus;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import retrofit.RetrofitError;
import timber.log.Timber;

import static com.battlelancer.seriesguide.provider.SeriesGuideContract.Movies;
import static com.battlelancer.seriesguide.sync.SgSyncAdapter.UpdateResult;

public class MovieTools {

    public static class MovieChangedEvent {
        public int movieTmdbId;

        public MovieChangedEvent(int movieTmdbId) {
            this.movieTmdbId = movieTmdbId;
        }
    }

    public static void addToCollection(Context context, int movieTmdbId) {
        if (TraktCredentials.get(context).hasCredentials()) {
            if (!Utils.isConnected(context, true)) {
                return;
            }
            // add to trakt collection
            AndroidUtils.executeOnPool(
                    new TraktTask(context).collectionAddMovie(movieTmdbId)
            );
        }

        // make modifications to local database
        addToList(context, movieTmdbId, Movies.IN_COLLECTION, AddMovieTask.AddTo.COLLECTION);
    }

    public static void addToWatchlist(Context context, int movieTmdbId) {
        if (TraktCredentials.get(context).hasCredentials()) {
            if (!Utils.isConnected(context, true)) {
                return;
            }
            // add to trakt watchlist
            AndroidUtils.executeOnPool(
                    new TraktTask(context).watchlistMovie(movieTmdbId)
            );
        }

        // make modifications to local database
        addToList(context, movieTmdbId, Movies.IN_WATCHLIST, AddMovieTask.AddTo.WATCHLIST);
    }

    private static void addToList(Context context, int movieTmdbId, String listColumn,
            AddMovieTask.AddTo list) {
        // do we have this movie in the database already?
        Boolean movieExists = isMovieExists(context, movieTmdbId);
        if (movieExists == null) {
            return;
        }
        if (movieExists) {
            updateMovie(context, movieTmdbId, listColumn, true);
        } else {
            addMovieAsync(context, movieTmdbId, list);
        }
    }

    public static void removeFromCollection(Context context, int movieTmdbId) {
        if (TraktCredentials.get(context).hasCredentials()) {
            if (!Utils.isConnected(context, true)) {
                return;
            }
            // remove from trakt collection
            AndroidUtils.executeOnPool(
                    new TraktTask(context).collectionRemoveMovie(movieTmdbId)
            );
        }

        // make modifications to local database
        Boolean isInWatchlist = isMovieInList(context, movieTmdbId, Movies.IN_WATCHLIST);
        removeFromList(context, movieTmdbId, isInWatchlist, Movies.IN_COLLECTION);
    }

    public static void removeFromWatchlist(Context context, int movieTmdbId) {
        if (TraktCredentials.get(context).hasCredentials()) {
            if (!Utils.isConnected(context, true)) {
                return;
            }
            // remove from trakt watchlist
            AndroidUtils.executeOnPool(
                    new TraktTask(context).unwatchlistMovie(movieTmdbId)
            );
        }

        // make modifications to local database
        Boolean isInCollection = isMovieInList(context, movieTmdbId, Movies.IN_COLLECTION);
        removeFromList(context, movieTmdbId, isInCollection, Movies.IN_WATCHLIST);
    }

    private static void removeFromList(Context context, int movieTmdbId, Boolean isInOtherList,
            String listColumn) {
        if (isInOtherList == null) {
            return;
        }
        if (isInOtherList) {
            // just update list flag
            updateMovie(context, movieTmdbId, listColumn, false);
        } else {
            // completely remove from database
            deleteMovie(context, movieTmdbId);
        }
    }

    public static void watchedMovie(Context context, int movieTmdbId) {
        if (TraktCredentials.get(context).hasCredentials()) {
            if (!Utils.isConnected(context, true)) {
                return;
            }
            // remove from trakt watchlist
            AndroidUtils.executeOnPool(
                    new TraktTask(context).watchedMovie(movieTmdbId)
            );
        }

        // try updating local movie (if any)
        updateMovie(context, movieTmdbId, Movies.WATCHED, true);
    }

    public static void unwatchedMovie(Context context, int movieTmdbId) {
        if (TraktCredentials.get(context).hasCredentials()) {
            if (!Utils.isConnected(context, true)) {
                return;
            }
            // remove from trakt watchlist
            AndroidUtils.executeOnPool(
                    new TraktTask(context).unwatchedMovie(movieTmdbId)
            );
        }

        // try updating local movie (if any)
        updateMovie(context, movieTmdbId, Movies.WATCHED, false);
    }

    private static void addMovieAsync(Context context, int movieTmdbId, AddMovieTask.AddTo addTo) {
        Utils.executeInOrder(new AddMovieTask(context, addTo), movieTmdbId);
    }

    private static ContentValues[] buildMoviesContentValues(List<MovieDetails> movies) {
        ContentValues[] valuesArray = new ContentValues[movies.size()];
        int index = 0;
        for (MovieDetails movie : movies) {
            valuesArray[index] = buildMovieContentValues(movie);
            index++;
        }
        return valuesArray;
    }

    private static ContentValues buildMovieContentValues(MovieDetails details) {
        ContentValues values = buildBasicMovieContentValuesWithId(details);

        values.put(Movies.IN_COLLECTION,
                DBUtils.convertBooleanToInt(details.traktMovie().inCollection));
        values.put(Movies.IN_WATCHLIST,
                DBUtils.convertBooleanToInt(details.traktMovie().inWatchlist));

        return values;
    }

    /**
     * Extracts basic properties, except in_watchlist and in_collection from trakt. Also includes
     * the TMDb id as value.
     */
    private static ContentValues buildBasicMovieContentValuesWithId(MovieDetails details) {
        ContentValues values = buildBasicMovieContentValues(details);
        values.put(Movies.TMDB_ID, details.tmdbMovie().id);
        return values;
    }

    /**
     * Extracts basic properties, except in_watchlist and in_collection from trakt.
     */
    public static ContentValues buildBasicMovieContentValues(MovieDetails details) {
        ContentValues values = new ContentValues();

        // data from trakt
        if (details.traktMovie() != null) {
            values.put(Movies.RELEASED_UTC_MS, details.traktMovie().released.getTime());
            values.put(Movies.WATCHED, DBUtils.convertBooleanToInt(details.traktMovie().watched));
            if (details.traktMovie().ratings != null) {
                values.put(Movies.RATING_TRAKT, details.traktMovie().ratings.percentage);
                values.put(Movies.RATING_VOTES_TRAKT, details.traktMovie().ratings.votes);
            }
        }

        // data from TMDb
        if (details.tmdbMovie() != null) {
            values.put(Movies.IMDB_ID, details.tmdbMovie().imdb_id);
            values.put(Movies.TITLE, details.tmdbMovie().title);
            values.put(Movies.TITLE_NOARTICLE,
                    DBUtils.trimLeadingArticle(details.tmdbMovie().title));
            values.put(Movies.OVERVIEW, details.tmdbMovie().overview);
            values.put(Movies.POSTER, details.tmdbMovie().poster_path);
            values.put(Movies.RUNTIME_MIN, details.tmdbMovie().runtime);
            values.put(Movies.RATING_TMDB, details.tmdbMovie().vote_average);
        }

        return values;
    }

    private static void deleteMovie(Context context, int movieTmdbId) {
        context.getContentResolver().delete(Movies.buildMovieUri(movieTmdbId), null, null);

        EventBus.getDefault().post(new MovieChangedEvent(movieTmdbId));
    }

    /**
     * Returns a set of the TMDb ids of all movies in the local database.
     *
     * @return null if there was an error, empty list if there are no movies.
     */
    private static HashSet<Integer> getMovieTmdbIdsAsSet(Context context) {
        HashSet<Integer> localMoviesIds = new HashSet<>();

        Cursor movies = context.getContentResolver().query(Movies.CONTENT_URI,
                new String[] { Movies._ID, Movies.TMDB_ID }, null, null, null);
        if (movies == null) {
            return null;
        }

        while (movies.moveToNext()) {
            localMoviesIds.add(movies.getInt(1));
        }

        movies.close();

        return localMoviesIds;
    }

    /**
     * Determines if the given movie is in the list determined by the given database column name.
     *
     * @return true if the movie is in the given list, false otherwise. Can return {@code null} if
     * the database could not be queried or the movie does not exist.
     */
    private static Boolean isMovieInList(Context context, int movieTmdbId, String listColumn) {
        Cursor movie = context.getContentResolver().query(Movies.buildMovieUri(movieTmdbId),
                new String[] { listColumn }, null, null, null);
        if (movie == null || !movie.moveToFirst()) {
            return null;
        }

        boolean isInList = movie.getInt(0) == 1;

        movie.close();

        return isInList;
    }

    private static Boolean isMovieExists(Context context, int movieTmdbId) {
        Cursor movie = context.getContentResolver().query(Movies.CONTENT_URI, new String[] {
                Movies._ID }, Movies.TMDB_ID + "=" + movieTmdbId, null, null);
        if (movie == null) {
            return null;
        }

        boolean movieExists = movie.getCount() > 0;

        movie.close();

        return movieExists;
    }

    private static void updateMovie(Context context, int movieTmdbId, String column,
            boolean value) {
        ContentValues values = new ContentValues();
        values.put(column, value);
        context.getContentResolver().update(Movies.buildMovieUri(movieTmdbId), values, null, null);

        EventBus.getDefault().post(new MovieChangedEvent(movieTmdbId));
    }

    private static class AddMovieTask extends AsyncTask<Integer, Void, Integer> {

        private final Context mContext;

        private final AddTo mAddTo;

        public enum AddTo {
            COLLECTION,
            WATCHLIST
        }

        public AddMovieTask(Context context, AddTo addTo) {
            mContext = context;
            mAddTo = addTo;
        }

        @Override
        protected Integer doInBackground(Integer... params) {
            int movieTmdbId = params[0];

            // get movie info
            MovieDetails details = Download.getMovieDetails(mContext, movieTmdbId);
            if (details.traktMovie() == null || details.tmdbMovie() == null) {
                return null;
            }

            // store in database, overwrite in_collection and in_watchlist
            ContentValues values = buildBasicMovieContentValuesWithId(details);
            values.put(Movies.IN_COLLECTION, mAddTo == AddTo.COLLECTION ?
                    1 : DBUtils.convertBooleanToInt(details.traktMovie().inCollection));
            values.put(Movies.IN_WATCHLIST, mAddTo == AddTo.WATCHLIST ?
                    1 : DBUtils.convertBooleanToInt(details.traktMovie().inWatchlist));

            mContext.getContentResolver().insert(Movies.CONTENT_URI, values);

            return movieTmdbId;
        }

        @Override
        protected void onPostExecute(Integer movieTmdbId) {
            // guard against NPE https://github.com/UweTrottmann/SeriesGuide/issues/371
            if (movieTmdbId != null) {
                EventBus.getDefault().post(new MovieChangedEvent(movieTmdbId));
            }
        }
    }

    public static class Download {

        /**
         * Updates the movie local database against trakt movie watchlist and collection, therefore
         * adds, updates and removes movies in the database.<br/>Performs <b>synchronous network
         * access</b>, so make sure to run this on a background thread!
         */
        public static UpdateResult syncMoviesFromTrakt(Context context) {
            Trakt trakt = ServiceUtils.getTraktWithAuth(context);
            if (trakt == null) {
                // trakt is not connected, we are done here
                return UpdateResult.SUCCESS;
            }
            UserService userService = trakt.userService();

            HashSet<Integer> localMovies = getMovieTmdbIdsAsSet(context);
            HashSet<Integer> moviesToRemove = new HashSet<>(localMovies);
            HashSet<Integer> moviesToAdd = new HashSet<>();
            ArrayList<ContentProviderOperation> batch = new ArrayList<>();

            // get trakt watchlist
            List<Movie> watchlistMovies;
            try {
                watchlistMovies = userService
                        .watchlistMovies(TraktCredentials.get(context).getUsername());
            } catch (RetrofitError e) {
                return UpdateResult.INCOMPLETE;
            }
            if (watchlistMovies == null) {
                return UpdateResult.INCOMPLETE;
            }

            // build watchlist updates
            ContentValues values = new ContentValues();
            values.put(Movies.IN_WATCHLIST, true);
            buildMovieUpdateOps(watchlistMovies, localMovies, moviesToAdd, moviesToRemove, batch,
                    values);

            // apply watchlist updates
            try {
                DBUtils.applyInSmallBatches(context, batch);
            } catch (OperationApplicationException e) {
                Timber.e(e, "Applying watchlist updates failed");
                return UpdateResult.INCOMPLETE;
            }
            batch.clear();
            values.clear();

            // return if connectivity is lost
            if (!AndroidUtils.isNetworkConnected(context)) {
                return UpdateResult.INCOMPLETE;
            }

            // get trakt collection
            List<Movie> collectionMovies;
            try {
                collectionMovies = userService.libraryMoviesCollection(
                        TraktCredentials.get(context).getUsername(), Extended.MIN);
            } catch (RetrofitError e) {
                return UpdateResult.INCOMPLETE;
            }
            if (collectionMovies == null) {
                return UpdateResult.INCOMPLETE;
            }

            // build collection updates
            values.put(Movies.IN_COLLECTION, true);
            buildMovieUpdateOps(collectionMovies, localMovies, moviesToAdd, moviesToRemove, batch,
                    values);

            // apply collection updates
            try {
                DBUtils.applyInSmallBatches(context, batch);
            } catch (OperationApplicationException e) {
                Timber.e(e, "Applying collection updates failed");
                return UpdateResult.INCOMPLETE;
            }
            batch.clear();

            // merge on first run, delete on consequent runs
            if (TraktSettings.hasMergedMovies(context)) {
                // remove movies not on trakt
                buildMovieDeleteOps(moviesToRemove, batch);
                try {
                    DBUtils.applyInSmallBatches(context, batch);
                } catch (OperationApplicationException e) {
                    Timber.e(e, "Removing movies failed");
                    return UpdateResult.INCOMPLETE;
                }
            } else {
                // upload movies not on trakt
                UpdateResult result = Upload.uploadMovies(context, trakt, moviesToRemove);
                if (result != UpdateResult.SUCCESS) {
                    // abort here if there were issues
                    return result;
                } else {
                    // flag that we ran a successful merge
                    PreferenceManager.getDefaultSharedPreferences(context).edit()
                            .putBoolean(TraktSettings.KEY_HAS_MERGED_MOVIES, true).commit();
                }
            }

            // return if connectivity is lost
            if (!AndroidUtils.isNetworkConnected(context)) {
                return UpdateResult.INCOMPLETE;
            }

            // add movies new from trakt
            return addMovies(context, trakt, moviesToAdd.toArray(new Integer[moviesToAdd.size()]));
        }

        /**
         * Downloads movie summaries from trakt and adds them to the database.
         */
        private static UpdateResult addMovies(Context context, Trakt trakt,
                Integer... movieTmdbIds) {
            MovieService movieServiceTrakt = trakt.movieService();
            MoviesService moviesServiceTmdb = ServiceUtils.getTmdb(context)
                    .moviesService();
            String languageCode = DisplaySettings.getContentLanguage(context);
            List<MovieDetails> movies = new LinkedList<>();

            for (int i = 0; i < movieTmdbIds.length; i++) {
                if (!AndroidUtils.isNetworkConnected(context)) {
                    return UpdateResult.INCOMPLETE;
                }

                MovieDetails movieDetails = getMovieDetails(movieServiceTrakt, moviesServiceTmdb,
                        languageCode, movieTmdbIds[i]);
                if (movieDetails.traktMovie() == null || movieDetails.tmdbMovie() == null) {
                    // TODO abort if server looks unreachable (check http status)
                    // skip this one
                    continue;
                }

                movies.add(movieDetails);

                // process in batches of at most 10
                if (i % 10 == 0 || i == movieTmdbIds.length - 1) {
                    // insert into database
                    context.getContentResolver()
                            .bulkInsert(Movies.CONTENT_URI, buildMoviesContentValues(movies));

                    // reset
                    movies.clear();
                }
            }

            return UpdateResult.SUCCESS;
        }

        /**
         * Download movie data from trakt and TMDb. If you plan on calling this multiple times, use
         * {@link #getMovieDetails(com.jakewharton.trakt.services.MovieService,
         * com.uwetrottmann.tmdb.services.MoviesService, String, int)} instead.
         */
        public static MovieDetails getMovieDetails(Context context, int movieTmdbId) {
            // trakt
            Trakt trakt = ServiceUtils.getTraktWithAuth(context);
            if (trakt == null) {
                trakt = ServiceUtils.getTrakt(context);
            }
            MovieService movieService = trakt.movieService();

            // TMDb
            MoviesService moviesService = ServiceUtils.getTmdb(context).moviesService();
            String languageCode = DisplaySettings.getContentLanguage(context);

            return getMovieDetails(movieService, moviesService, languageCode, movieTmdbId);
        }

        /**
         * Download movie data from trakt and TMDb.
         */
        public static MovieDetails getMovieDetails(MovieService movieServiceTrakt,
                MoviesService moviesServiceTmdb, String languageCode, int movieTmdbId) {
            Movie traktMovie = loadFromTrakt(movieServiceTrakt, movieTmdbId);
            com.uwetrottmann.tmdb.entities.Movie tmdbMovie = loadFromTmdb(moviesServiceTmdb,
                    languageCode, movieTmdbId);

            MovieDetails details = new MovieDetails();
            details.traktMovie(traktMovie);
            details.tmdbMovie(tmdbMovie);

            return details;
        }

        private static Movie loadFromTrakt(MovieService movieService, int movieTmdbId) {
            try {
                return movieService.summary(movieTmdbId);
            } catch (RetrofitError e) {
                Timber.e(e, "Loading trakt movie summary failed");
                return null;
            }
        }

        private static com.uwetrottmann.tmdb.entities.Movie loadFromTmdb(
                MoviesService moviesService, String languageCode, int movieTmdbId) {
            try {
                com.uwetrottmann.tmdb.entities.Movie movie = moviesService.summary(movieTmdbId,
                        languageCode);
                if (movie != null && TextUtils.isEmpty(movie.overview)) {
                    // fall back to English if TMDb has no localized text
                    movie = moviesService.summary(movieTmdbId);
                }
                return movie;
            } catch (RetrofitError e) {
                Timber.e(e, "Loading TMDb movie summary failed");
                return null;
            }
        }

        private static void buildMovieUpdateOps(List<Movie> remoteMovies,
                HashSet<Integer> localMovies, HashSet<Integer> moviesToAdd,
                HashSet<Integer> moviesToRemove, ArrayList<ContentProviderOperation> batch,
                ContentValues values) {
            for (Movie movie : remoteMovies) {
                if (localMovies.contains(movie.tmdbId)) {
                    // update existing movie
                    ContentProviderOperation op = ContentProviderOperation
                            .newUpdate(Movies.buildMovieUri(movie.tmdbId))
                            .withValues(values).build();
                    batch.add(op);

                    // prevent movie from getting removed
                    moviesToRemove.remove(movie.tmdbId);
                } else {
                    // insert new movie
                    moviesToAdd.add(movie.tmdbId);
                }
            }
        }

        private static void buildMovieDeleteOps(HashSet<Integer> moviesToRemove,
                ArrayList<ContentProviderOperation> batch) {
            for (Integer movieTmdbId : moviesToRemove) {
                ContentProviderOperation op = ContentProviderOperation
                        .newDelete(Movies.buildMovieUri(movieTmdbId)).build();
                batch.add(op);
            }
        }
    }

    private static class Upload {

        /**
         * Uploads the given movies to the appropriate list(s) on trakt.
         */
        public static UpdateResult uploadMovies(Context context, Trakt trakt,
                HashSet<Integer> moviesToUpload) {
            if (moviesToUpload.size() == 0) {
                // nothing to upload
                return UpdateResult.SUCCESS;
            }

            // return if connectivity is lost
            if (!AndroidUtils.isNetworkConnected(context)) {
                return UpdateResult.INCOMPLETE;
            }

            Cursor localMovies = context.getContentResolver().query(Movies.CONTENT_URI,
                    new String[] { Movies._ID, Movies.TMDB_ID, Movies.IN_COLLECTION,
                            Movies.IN_WATCHLIST }, null, null, null);
            if (localMovies == null) {
                return UpdateResult.INCOMPLETE;
            }

            // build list of collected, watchlisted movies to upload
            List<MovieService.SeenMovie> moviesToCollect = new LinkedList<>();
            List<MovieService.SeenMovie> moviesToWatchlist = new LinkedList<>();
            while (localMovies.moveToNext()) {
                int tmdbId = localMovies.getInt(1);
                if (!moviesToUpload.contains(tmdbId)) {
                    continue;
                }

                MovieService.SeenMovie movie = new MovieService.SeenMovie(tmdbId);

                // in collection?
                if (localMovies.getInt(2) == 1) {
                    moviesToCollect.add(movie);
                }
                // in watchlist?
                if (localMovies.getInt(3) == 1) {
                    moviesToWatchlist.add(movie);
                }
            }

            // clean up
            localMovies.close();

            // upload
            try {
                MovieService movieService = trakt.movieService();
                if (moviesToCollect.size() > 0) {
                    movieService.library(new MovieService.Movies(moviesToCollect));
                }
                if (moviesToWatchlist.size() > 0) {
                    movieService.watchlist(new MovieService.Movies(moviesToWatchlist));
                }
            } catch (RetrofitError e) {
                Timber.e(e, "Uploading movies to watchlist or collection failed");
                return UpdateResult.INCOMPLETE;
            }

            return UpdateResult.SUCCESS;
        }
    }
}
