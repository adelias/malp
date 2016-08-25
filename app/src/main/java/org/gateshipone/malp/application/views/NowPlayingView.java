/*
 * Copyright (C) 2016  Hendrik Borghorst & Frederik Luetkes
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.gateshipone.malp.application.views;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.support.v4.view.ViewCompat;
import android.support.v4.widget.ViewDragHelper;
import android.support.v7.app.AppCompatActivity;
import android.util.AttributeSet;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.ViewSwitcher;


import java.util.Timer;

import org.gateshipone.malp.R;
import org.gateshipone.malp.application.callbacks.OnSaveDialogListener;
import org.gateshipone.malp.application.fragments.SaveDialog;
import org.gateshipone.malp.application.fragments.serverfragments.ChoosePlaylistDialog;
import org.gateshipone.malp.application.utils.FormatHelper;
import org.gateshipone.malp.application.utils.ThemeUtils;
import org.gateshipone.malp.mpdservice.handlers.MPDConnectionStateChangeHandler;
import org.gateshipone.malp.mpdservice.handlers.MPDStatusChangeHandler;
import org.gateshipone.malp.mpdservice.handlers.serverhandler.MPDCommandHandler;
import org.gateshipone.malp.mpdservice.handlers.serverhandler.MPDQueryHandler;
import org.gateshipone.malp.mpdservice.handlers.serverhandler.MPDStateMonitoringHandler;;
import org.gateshipone.malp.mpdservice.mpdprotocol.mpdobjects.MPDCurrentStatus;
import org.gateshipone.malp.mpdservice.mpdprotocol.mpdobjects.MPDFile;

public class NowPlayingView extends RelativeLayout implements PopupMenu.OnMenuItemClickListener {

    private final ViewDragHelper mDragHelper;

    private ServerStatusListener mStateListener;

    private ServerConnectionListener mConnectionStateListener;

    /**
     * Upper view part which is dragged up & down
     */
    private View mHeaderView;

    /**
     * Main view of draggable part
     */
    private View mMainView;

    private LinearLayout mDraggedUpButtons;
    private LinearLayout mDraggedDownButtons;

    /**
     * Absolute pixel position of upper layout bound
     */
    private int mTopPosition;

    /**
     * relative dragposition
     */
    private float mDragOffset;

    /**
     * Height of non-draggable part.
     * (Layout height - draggable part)
     */
    private int mDragRange;

    /**
     * Main cover imageview
     */
    private ImageView mCoverImage;

    /**
     * Small cover image, part of the draggable header
     */
    private ImageView mTopCoverImage;

    /**
     * View that contains the playlist ListVIew
     */
    private CurrentPlaylistView mPlaylistView;

    /**
     * ViewSwitcher used for switching between the main cover image and the playlist
     */
    private ViewSwitcher mViewSwitcher;


    /**
     * Timer that periodically updates the state of the view (seekbar)
     */
    private Timer mRefreshTimer = null;

    /**
     * Observer for information about the state of the draggable part of this view.
     * This is probably the Activity of which this view is part of.
     * (Used for smooth statusbar transition and state resuming)
     */
    private NowPlayingDragStatusReceiver mDragStatusReceiver = null;

    /**
     * Top buttons in the draggable header part.
     */
    private ImageButton mTopPlayPauseButton;
    private ImageButton mTopPlaylistButton;
    private ImageButton mTopMenuButton;

    private int mTopPlaylistButtonHeight;

    /**
     * Buttons in the bottom part of the view
     */
    private ImageButton mBottomRepeatButton;
    private ImageButton mBottomPreviousButton;
    private ImageButton mBottomPlayPauseButton;
    private ImageButton mBottomStopButton;
    private ImageButton mBottomNextButton;
    private ImageButton mBottomRandomButton;

    /**
     * Seekbar used for seeking and informing the user of the current playback position.
     */
    private SeekBar mPositionSeekbar;

    /**
     * Seekbar used for volume control of host
     */
    private SeekBar mVolumeSeekbar;

    private LinearLayout mHeaderTextLayout;
    private LayoutParams mHeaderTextLayoutParams;


    /**
     * Various textviews for track information
     */
    private TextView mTrackName;
    private TextView mTrackAdditionalInfo;
    private TextView mElapsedTime;
    private TextView mDuration;

    private TextView mTrackNo;
    private TextView mPlaylistNo;
    private TextView mBitrate;
    private TextView mAudioProperties;
    private TextView mTrackURI;




    private MPDCurrentStatus mLastStatus;

    /**
     * Name of the last played album. This is used for a optimization of cover fetching. If album
     * did not change with a track, there is no need to refetch the cover.
     */
    private String mLastAlbumKey;

    public NowPlayingView(Context context) {
        this(context, null, 0);
    }

    public NowPlayingView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NowPlayingView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mDragHelper = ViewDragHelper.create(this, 1f, new BottomDragCallbackHelper());
        mStateListener = new ServerStatusListener();
        mConnectionStateListener = new ServerConnectionListener();
    }

    /**
     * Maximizes this view with an animation.
     */
    public void maximize() {
        smoothSlideTo(0f);
    }

    /**
     * Minimizes the view with an animation.
     */
    public void minimize() {
        smoothSlideTo(1f);
    }

    /**
     * Slides the view to the given position.
     *
     * @param slideOffset 0.0 - 1.0 (0.0 is dragged down, 1.0 is dragged up)
     * @return If the move was successful
     */
    boolean smoothSlideTo(float slideOffset) {
        final int topBound = getPaddingTop();
        int y = (int) (topBound + slideOffset * mDragRange);

        if (mDragHelper.smoothSlideViewTo(mHeaderView, mHeaderView.getLeft(), y)) {
            ViewCompat.postInvalidateOnAnimation(this);
            return true;
        }
        return false;
    }


    /**
     * Set the position of the draggable view to the given offset. This is done without an animation.
     * Can be used to resume a certain state of the view (e.g. on resuming an activity)
     *
     * @param offset Offset to position the view to from 0.0 - 1.0 (0.0 dragged up, 1.0 dragged down)
     */
    public void setDragOffset(float offset) {
        if (offset > 1.0f || offset < 0.0f) {
            mDragOffset = 1.0f;
        }
        mDragOffset = offset;

        invalidate();
        requestLayout();


        // Set inverse alpha values for smooth layout transition.
        // Visibility still needs to be set otherwise parts of the buttons
        // are not clickable.
        mDraggedDownButtons.setAlpha(mDragOffset);
        mDraggedUpButtons.setAlpha(1.0f - mDragOffset);

        // Calculate the margin to smoothly resize text field
        LayoutParams layoutParams = (LayoutParams) mHeaderTextLayout.getLayoutParams();
        layoutParams.setMarginEnd((int) (mTopPlaylistButton.getWidth() * (1.0 - mDragOffset)));
        mHeaderTextLayout.setLayoutParams(layoutParams);

        // Notify the observers about the change
        if (mDragStatusReceiver != null) {
            mDragStatusReceiver.onDragPositionChanged(offset);
        }

        if (mDragOffset == 0.0f) {
            // top
            mDraggedDownButtons.setVisibility(INVISIBLE);
            mDraggedUpButtons.setVisibility(VISIBLE);
            if (mDragStatusReceiver != null) {
                mDragStatusReceiver.onStatusChanged(NowPlayingDragStatusReceiver.DRAG_STATUS.DRAGGED_UP);
            }
        } else {
            // bottom
            mDraggedDownButtons.setVisibility(VISIBLE);
            mDraggedUpButtons.setVisibility(INVISIBLE);
            if (mDragStatusReceiver != null) {
                mDragStatusReceiver.onStatusChanged(NowPlayingDragStatusReceiver.DRAG_STATUS.DRAGGED_DOWN);
            }
        }
    }

    /**
     * Menu click listener. This method gets called when the user selects an item of the popup menu (right top corner).
     *
     * @param item MenuItem that was clicked.
     * @return Returns true if the item was handled by this method. False otherwise.
     */
    @Override
    public boolean onMenuItemClick(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.view_nowplaying_action_clearplaylist:
                MPDQueryHandler.clearPlaylist();
                break;
            case R.id.view_nowplaying_action_saveplaylist:
                OnSaveDialogListener plDialogCallback = new OnSaveDialogListener() {
                    @Override
                    public void onSaveObject(String title) {
                        MPDQueryHandler.savePlaylist(title);
                    }

                    @Override
                    public void onCreateNewObject() {
                        // open dialog in order to save the current playlist as a playlist in the mediastore
                        SaveDialog textDialog = new SaveDialog();
                        Bundle args = new Bundle();
                        args.putString(SaveDialog.EXTRA_DIALOG_TITLE, getResources().getString(R.string.dialog_save_playlist));
                        args.putString(SaveDialog.EXTRA_DIALOG_TEXT, getResources().getString(R.string.default_playlist_title));

                        textDialog.setCallback(this);
                        textDialog.setArguments(args);
                        textDialog.show(((AppCompatActivity) getContext()).getSupportFragmentManager(), "SavePLTextDialog");
                    }
                };

                // open dialog in order to save the current playlist as a playlist in the mediastore
                ChoosePlaylistDialog choosePlaylistDialog = new ChoosePlaylistDialog();
                Bundle args = new Bundle();
                args.putBoolean(ChoosePlaylistDialog.EXTRA_SHOW_NEW_ENTRY, true);

                choosePlaylistDialog.setCallback(plDialogCallback);
                choosePlaylistDialog.setArguments(args);
                choosePlaylistDialog.show(((AppCompatActivity) getContext()).getSupportFragmentManager(), "ChoosePlaylistDialog");
                break;
            case R.id.action_jump_to_current:
                mPlaylistView.jumpToCurrentSong();
                break;
            default:
                return false;
        }
        return false;
    }

    /**
     * Saves the current playlist. This just calls the PBS and asks him to save the playlist.
     *
     * @param playlistName Name of the playlist to save.
     */
    public void savePlaylist(String playlistName) {
        // FIXME savePlaylist with name
    }


    /**
     * Observer class for changes of the drag status.
     */
    private class BottomDragCallbackHelper extends ViewDragHelper.Callback {

        /**
         * Checks if a given child view should act as part of the drag. This is only true for the header
         * element of this View-class.
         *
         * @param child     Child that was touched by the user
         * @param pointerId Id of the pointer used for touching the view.
         * @return True if the view should be allowed to be used as dragging part, false otheriwse.
         */
        @Override
        public boolean tryCaptureView(View child, int pointerId) {
            return child == mHeaderView;
        }

        /**
         * Called if the position of the draggable view is changed. This rerequests the layout of the view.
         *
         * @param changedView The view that was changed.
         * @param left        Left position of the view (should stay constant in this case)
         * @param top         Top position of the view
         * @param dx          Dimension of the width
         * @param dy          Dimension of the height
         */
        @Override
        public void onViewPositionChanged(View changedView, int left, int top, int dx, int dy) {
            // Save the heighest top position of this view.
            mTopPosition = top;

            // Calculate the new drag offset
            mDragOffset = (float) top / mDragRange;

            // Relayout this view
            requestLayout();

            // Set inverse alpha values for smooth layout transition.
            // Visibility still needs to be set otherwise parts of the buttons
            // are not clickable.
            mDraggedDownButtons.setAlpha(mDragOffset);
            mDraggedUpButtons.setAlpha(1.0f - mDragOffset);

            // Calculate the margin to smoothly resize text field
            LayoutParams layoutParams = (LayoutParams) mHeaderTextLayout.getLayoutParams();
            layoutParams.setMarginEnd((int) (mTopPlaylistButton.getWidth() * (1.0 - mDragOffset)));
            mHeaderTextLayout.setLayoutParams(layoutParams);

            if (mDragStatusReceiver != null) {
                mDragStatusReceiver.onDragPositionChanged(mDragOffset);
            }

        }

        /**
         * Called if the user lifts the finger(release the view) with a velocity
         *
         * @param releasedChild View that was released
         * @param xvel          x position of the view
         * @param yvel          y position of the view
         */
        @Override
        public void onViewReleased(View releasedChild, float xvel, float yvel) {
            int top = getPaddingTop();
            if (yvel > 0 || (yvel == 0 && mDragOffset > 0.5f)) {
                top += mDragRange;
            }
            // Snap the view to top/bottom position
            mDragHelper.settleCapturedViewAt(releasedChild.getLeft(), top);
            invalidate();
        }

        /**
         * Returns the range within a view is allowed to be dragged.
         *
         * @param child Child to get the dragrange for
         * @return Dragging range
         */
        @Override
        public int getViewVerticalDragRange(View child) {
            return mDragRange;
        }


        /**
         * Clamps (limits) the view during dragging to the top or bottom(plus header height)
         *
         * @param child Child that is being dragged
         * @param top   Top position of the dragged view
         * @param dy    Delta value of the height
         * @return The limited height value (or valid position inside the clamped range).
         */
        @Override
        public int clampViewPositionVertical(View child, int top, int dy) {
            final int topBound = getPaddingTop();
            int bottomBound = getHeight() - mHeaderView.getHeight() - mHeaderView.getPaddingBottom();

            final int newTop = Math.min(Math.max(top, topBound), bottomBound);

            return newTop;
        }

        /**
         * Called when the drag state changed. Informs observers that it is either dragged up or down.
         * Also sets the visibility of button groups in the header
         *
         * @param state New drag state
         */
        @Override
        public void onViewDragStateChanged(int state) {
            super.onViewDragStateChanged(state);

            // Check if the new state is the idle state. If then notify the observer (if one is registered)
            if (state == ViewDragHelper.STATE_IDLE) {
                // Enable scrolling of the text views
                mTrackName.setSelected(true);
                mTrackAdditionalInfo.setSelected(true);

                if (mDragOffset == 0.0f) {
                    // Called when dragged up
                    mDraggedDownButtons.setVisibility(INVISIBLE);
                    mDraggedUpButtons.setVisibility(VISIBLE);
                    if (mDragStatusReceiver != null) {
                        mDragStatusReceiver.onStatusChanged(NowPlayingDragStatusReceiver.DRAG_STATUS.DRAGGED_UP);
                    }
                } else {
                    // Called when dragged down
                    mDraggedDownButtons.setVisibility(VISIBLE);
                    mDraggedUpButtons.setVisibility(INVISIBLE);
                    if (mDragStatusReceiver != null) {
                        mDragStatusReceiver.onStatusChanged(NowPlayingDragStatusReceiver.DRAG_STATUS.DRAGGED_DOWN);
                    }

                }
            } else if ( state == ViewDragHelper.STATE_DRAGGING) {
                /*
                 * Show both layouts to enable a smooth transition via
                 * alpha values of the layouts.
                 */
                mDraggedDownButtons.setVisibility(VISIBLE);
                mDraggedUpButtons.setVisibility(VISIBLE);
                // report the change of the view
                if (mDragStatusReceiver != null) {
                    // Disable scrolling of the text views
                    mTrackName.setSelected(false);
                    mTrackAdditionalInfo.setSelected(false);

                    mDragStatusReceiver.onStartDrag();

                    if (mViewSwitcher.getCurrentView() == mPlaylistView && mDragOffset == 1.0f) {
                        mPlaylistView.jumpToCurrentSong();
                    }
                }

            }
        }
    }

    /**
     * Informs the dragHelper about a scroll movement.
     */
    @Override
    public void computeScroll() {
        // Continues the movement of the View Drag Helper and sets the invalidation for this View
        // if the animation is not finished and needs continuation
        if (mDragHelper.continueSettling(true)) {
            ViewCompat.postInvalidateOnAnimation(this);
        }
    }

    /**
     * Handles touch inputs to some views, to make sure, the ViewDragHelper is called.
     *
     * @param ev Touch input event
     * @return True if handled by this view or false otherwise
     */
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        // Call the drag helper
        mDragHelper.processTouchEvent(ev);

        // Get the position of the new touch event
        final float x = ev.getX();
        final float y = ev.getY();

        // Check if the position lies in the bounding box of the header view (which is draggable)
        boolean isHeaderViewUnder = mDragHelper.isViewUnder(mHeaderView, (int) x, (int) y);

        // Check if drag is handled by the helper, or the header or mainview. If not notify the system that input is not yet handled.
        return isHeaderViewUnder && isViewHit(mHeaderView, (int) x, (int) y) || isViewHit(mMainView, (int) x, (int) y);
    }


    /**
     * Checks if an input to coordinates lay within a View
     *
     * @param view View to check with
     * @param x    x value of the input
     * @param y    y value of the input
     * @return
     */
    private boolean isViewHit(View view, int x, int y) {
        int[] viewLocation = new int[2];
        view.getLocationOnScreen(viewLocation);
        int[] parentLocation = new int[2];
        this.getLocationOnScreen(parentLocation);
        int screenX = parentLocation[0] + x;
        int screenY = parentLocation[1] + y;
        return screenX >= viewLocation[0] && screenX < viewLocation[0] + view.getWidth() &&
                screenY >= viewLocation[1] && screenY < viewLocation[1] + view.getHeight();
    }

    /**
     * Asks the ViewGroup about the size of all its children and paddings around.
     *
     * @param widthMeasureSpec  The width requirements for this view
     * @param heightMeasureSpec The height requirements for this view
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        measureChildren(widthMeasureSpec, heightMeasureSpec);

        int maxWidth = MeasureSpec.getSize(widthMeasureSpec);
        int maxHeight = MeasureSpec.getSize(heightMeasureSpec);

        setMeasuredDimension(resolveSizeAndState(maxWidth, widthMeasureSpec, 0),
                resolveSizeAndState(maxHeight, heightMeasureSpec, 0));

        // Calculate the margin to smoothly resize text field
        LayoutParams layoutParams = (LayoutParams) mHeaderTextLayout.getLayoutParams();
        layoutParams.setMarginEnd((int) (mTopPlaylistButton.getMeasuredHeight() * (1.0 - mDragOffset)));
        mHeaderTextLayout.setLayoutParams(layoutParams);


        ViewGroup.LayoutParams imageParams = mCoverImage.getLayoutParams();
        imageParams.height = mViewSwitcher.getMeasuredHeight();
        mCoverImage.setLayoutParams(imageParams);
    }

    /**
     * Called after the layout inflater is finished.
     * Sets all global view variables to the ones inflated.
     */
    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        // Get both main views (header and bottom part)
        mHeaderView = findViewById(R.id.now_playing_headerLayout);
        mMainView = findViewById(R.id.now_playing_bodyLayout);

        // header buttons
        mTopPlayPauseButton = (ImageButton) findViewById(R.id.now_playing_topPlayPauseButton);
        mTopPlaylistButton = (ImageButton) findViewById(R.id.now_playing_topPlaylistButton);
        mTopMenuButton = (ImageButton) findViewById(R.id.now_playing_topMenuButton);

        // bottom buttons
        mBottomRepeatButton = (ImageButton) findViewById(R.id.now_playing_bottomRepeatButton);
        mBottomPreviousButton = (ImageButton) findViewById(R.id.now_playing_bottomPreviousButton);
        mBottomPlayPauseButton = (ImageButton) findViewById(R.id.now_playing_bottomPlayPauseButton);
        mBottomStopButton = (ImageButton) findViewById(R.id.now_playing_bottomStopButton);
        mBottomNextButton = (ImageButton) findViewById(R.id.now_playing_bottomNextButton);
        mBottomRandomButton = (ImageButton) findViewById(R.id.now_playing_bottomRandomButton);

        // Main cover image
        mCoverImage = (ImageView) findViewById(R.id.now_playing_cover);
        // Small header cover image
        mTopCoverImage = (ImageView) findViewById(R.id.now_playing_topCover);

        // View with the ListView of the playlist
        mPlaylistView = (CurrentPlaylistView) findViewById(R.id.now_playing_playlist);

        // view switcher for cover and playlist view
        mViewSwitcher = (ViewSwitcher) findViewById(R.id.now_playing_view_switcher);

        // Button container for the buttons shown if dragged up
        mDraggedUpButtons = (LinearLayout) findViewById(R.id.now_playing_layout_dragged_up);
        // Button container for the buttons shown if dragged down
        mDraggedDownButtons = (LinearLayout) findViewById(R.id.now_playing_layout_dragged_down);

        // textviews
        mTrackName = (TextView) findViewById(R.id.now_playing_trackName);
        // For marquee scrolling the TextView need selected == true
        mTrackName.setSelected(true);
        mTrackAdditionalInfo = (TextView) findViewById(R.id.now_playing_track_additional_info);
        // For marquee scrolling the TextView need selected == true
        mTrackAdditionalInfo.setSelected(true);

        mTrackNo = (TextView) findViewById(R.id.now_playing_text_track_no);
        mPlaylistNo = (TextView) findViewById(R.id.now_playing_text_playlist_no);
        mBitrate = (TextView) findViewById(R.id.now_playing_text_bitrate);
        mAudioProperties = (TextView) findViewById(R.id.now_playing_text_audio_properties);
        mTrackURI = (TextView) findViewById(R.id.now_playing_text_track_uri);

        // Textviews directly under the seekbar
        mElapsedTime = (TextView) findViewById(R.id.now_playing_elapsedTime);
        mDuration = (TextView) findViewById(R.id.now_playing_duration);

        mHeaderTextLayout = (LinearLayout) findViewById(R.id.now_playing_header_textLayout);

        // seekbar (position)
        mPositionSeekbar = (SeekBar) findViewById(R.id.now_playing_seekBar);
        mPositionSeekbar.setOnSeekBarChangeListener(new PositionSeekbarListener());

        mVolumeSeekbar = (SeekBar) findViewById(R.id.volume_seekbar);
        mVolumeSeekbar.setMax(100);
        mVolumeSeekbar.setOnSeekBarChangeListener(new VolumeSeekBarListener());

        // set dragging part default to bottom
        mDragOffset = 1.0f;
        mDraggedUpButtons.setVisibility(INVISIBLE);
        mDraggedDownButtons.setVisibility(VISIBLE);
        mDraggedUpButtons.setAlpha(0.0f);

        // add listener to top playpause button
        mTopPlayPauseButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View arg0) {
                if (mLastStatus.getPlaybackState() == MPDCurrentStatus.MPD_PLAYBACK_STATE.MPD_PLAYING) {
                    MPDCommandHandler.pause();
                } else if (mLastStatus.getPlaybackState() == MPDCurrentStatus.MPD_PLAYBACK_STATE.MPD_PAUSING) {
                    MPDCommandHandler.play();
                } else if (mLastStatus.getPlaybackState() == MPDCurrentStatus.MPD_PLAYBACK_STATE.MPD_STOPPED) {
                    int lastIndex = mLastStatus.getCurrentSongIndex();
                    if (lastIndex >= 0) {
                        MPDCommandHandler.playSongIndex(mLastStatus.getCurrentSongIndex());
                    } else {
                        MPDCommandHandler.playSongIndex(0);
                    }
                }
            }
        });

        // Add listeners to top playlist button
        mTopPlaylistButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {

                // get color for playlist button
                int color;
                if (mViewSwitcher.getCurrentView() != mPlaylistView) {
                    color = ThemeUtils.getThemeColor(getContext(), R.attr.colorAccent);
                } else {
                    color = ThemeUtils.getThemeColor(getContext(), android.R.attr.textColor);
                }

                // tint the button
                mTopPlaylistButton.setImageTintList(ColorStateList.valueOf(color));

                // toggle between cover and playlistview
                mViewSwitcher.showNext();

                // report the change of the view
                if (mDragStatusReceiver != null) {
                    // set view status
                    if (mViewSwitcher.getCurrentView() == mCoverImage) {
                        // cover image is shown
                        mDragStatusReceiver.onSwitchedViews(NowPlayingDragStatusReceiver.VIEW_SWITCHER_STATUS.COVER_VIEW);
                    } else {
                        // playlist view is shown
                        mDragStatusReceiver.onSwitchedViews(NowPlayingDragStatusReceiver.VIEW_SWITCHER_STATUS.PLAYLIST_VIEW);
                        mPlaylistView.jumpToCurrentSong();
                    }
                }
            }
        });

        // Add listener to top menu button
        mTopMenuButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                showAdditionalOptionsMenu(v);
            }
        });

        // Add listener to bottom repeat button
        mBottomRepeatButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View arg0) {
                if (null != mLastStatus) {
                    if (mLastStatus.getRepeat() == 0) {
                        MPDCommandHandler.setRepeat(true);
                    } else {
                        MPDCommandHandler.setRepeat(false);
                    }
                }
            }
        });

        // Add listener to bottom previous button
        mBottomPreviousButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View arg0) {
                MPDCommandHandler.previousSong();

            }
        });

        // Add listener to bottom playpause button
        mBottomPlayPauseButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View arg0) {
                if (null != mLastStatus) {
                    if (mLastStatus.getPlaybackState() == MPDCurrentStatus.MPD_PLAYBACK_STATE.MPD_PLAYING) {
                        MPDCommandHandler.pause();
                    } else if (mLastStatus.getPlaybackState() == MPDCurrentStatus.MPD_PLAYBACK_STATE.MPD_PAUSING) {
                        MPDCommandHandler.play();
                    } else if (mLastStatus.getPlaybackState() == MPDCurrentStatus.MPD_PLAYBACK_STATE.MPD_STOPPED) {
                        int lastIndex = mLastStatus.getCurrentSongIndex();
                        if (lastIndex >= 0) {
                            MPDCommandHandler.playSongIndex(mLastStatus.getCurrentSongIndex());
                        } else {
                            MPDCommandHandler.playSongIndex(0);
                        }
                    }
                }
            }
        });

        mBottomStopButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                MPDCommandHandler.stop();
            }
        });

        // Add listener to bottom next button
        mBottomNextButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View arg0) {
                MPDCommandHandler.nextSong();
            }
        });

        // Add listener to bottom random button
        mBottomRandomButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View arg0) {
                if (null != mLastStatus) {
                    if (mLastStatus.getRandom() == 0) {
                        MPDCommandHandler.setRandom(true);
                    } else {
                        MPDCommandHandler.setRandom(false);
                    }
                }
            }
        });


        invalidate();


    }

    /**
     * Called to open the popup menu on the top right corner.
     *
     * @param v
     */
    private void showAdditionalOptionsMenu(View v) {
        PopupMenu menu = new PopupMenu(getContext(), v);
        // Inflate the menu from a menu xml file
        menu.inflate(R.menu.popup_menu_nowplaying);
        // Set the main NowPlayingView as a listener (directly implements callback)
        menu.setOnMenuItemClickListener(this);
        // Open the menu itself
        menu.show();
    }


    /**
     * Called when a layout is requested from the graphics system.
     *
     * @param changed If the layout is changed (size, ...)
     * @param l       Left position
     * @param t       Top position
     * @param r       Right position
     * @param b       Bottom position
     */
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        // Calculate the maximal range that the view is allowed to be dragged
        mDragRange = (getHeight() - mHeaderView.getHeight());

        // New temporary top position, to fix the view at top or bottom later if state is idle.
        int newTop = mTopPosition;

        // fix height at top or bottom if state idle
        if (mDragHelper.getViewDragState() == ViewDragHelper.STATE_IDLE) {
            newTop = (int) (mDragRange * mDragOffset);
        }

        // Request the upper part of the NowPlayingView (header)
        mHeaderView.layout(
                0,
                newTop,
                r,
                newTop + mHeaderView.getMeasuredHeight());

        // Request the lower part of the NowPlayingView (main part)
        mMainView.layout(
                0,
                newTop + mHeaderView.getMeasuredHeight(),
                r,
                newTop + b);



    }

    /**
     * Stop the refresh timer when the view is not visible to the user anymore.
     * Unregister the receiver for NowPlayingInformation intends, not needed anylonger.
     */
    public void onPause() {
        // Unregister listener
        MPDStateMonitoringHandler.unregisterStatusListener(mStateListener);
        MPDStateMonitoringHandler.unregisterConnectionStateListener(mConnectionStateListener);
        mPlaylistView.onPause();
    }

    /**
     * Resumes refreshing operation because the view is visible to the user again.
     * Also registers to the NowPlayingInformation intends again.
     */
    public void onResume() {

        // get the playbackservice, when the connection is successfully established the timer gets restarted

        // FIXME connect with MPD again

        // Reenable scrolling views after resuming
        if (mTrackName != null) {
            mTrackName.setSelected(true);
        }

        if (mTrackAdditionalInfo != null) {
            mTrackAdditionalInfo.setSelected(true);
        }

        invalidate();

        // Register with MPDStateMonitoring system
        MPDStateMonitoringHandler.registerStatusListener(mStateListener);
        MPDStateMonitoringHandler.registerConnectionStateListener(mConnectionStateListener);

        mPlaylistView.onResume();
    }


    private void updateMPDStatus(MPDCurrentStatus status) {

        MPDCurrentStatus.MPD_PLAYBACK_STATE state = status.getPlaybackState();

        // update play buttons
        switch (state) {
            case MPD_PLAYING:
                mTopPlayPauseButton.setImageResource(R.drawable.ic_pause_48dp);
                mBottomPlayPauseButton.setImageResource(R.drawable.ic_pause_circle_fill_48dp);


                break;
            case MPD_PAUSING:
            case MPD_STOPPED:
                mTopPlayPauseButton.setImageResource(R.drawable.ic_play_arrow_48dp);
                mBottomPlayPauseButton.setImageResource(R.drawable.ic_play_circle_fill_48dp);


                break;
        }

        // update repeat button
        // FIXME with single playback
        switch (status.getRepeat()) {
            case 0:
                mBottomRepeatButton.setImageResource(R.drawable.ic_repeat_24dp);
                mBottomRepeatButton.setImageTintList(ColorStateList.valueOf(ThemeUtils.getThemeColor(getContext(), android.R.attr.textColor)));
                break;
            case 1:
                mBottomRepeatButton.setImageResource(R.drawable.ic_repeat_24dp);
                mBottomRepeatButton.setImageTintList(ColorStateList.valueOf(ThemeUtils.getThemeColor(getContext(), android.R.attr.colorAccent)));
                break;
        }

        // update random button
        switch (status.getRandom()) {
            case 0:
                mBottomRandomButton.setImageTintList(ColorStateList.valueOf(ThemeUtils.getThemeColor(getContext(), android.R.attr.textColor)));
                break;
            case 1:
                mBottomRandomButton.setImageTintList(ColorStateList.valueOf(ThemeUtils.getThemeColor(getContext(), android.R.attr.colorAccent)));
                break;
        }

        // Update position seekbar & textviews
        mPositionSeekbar.setProgress(status.getElapsedTime());
        mPositionSeekbar.setMax(status.getTrackLength());

        mElapsedTime.setText(FormatHelper.formatTracktimeFromS(status.getElapsedTime()));
        mDuration.setText(FormatHelper.formatTracktimeFromS(status.getTrackLength()));

        // Update volume seekbar
        mVolumeSeekbar.setProgress(status.getVolume());


        mPlaylistNo.setText(String.valueOf(status.getCurrentSongIndex()) + getResources().getString(R.string.track_number_album_count_separator) +
                String.valueOf(status.getPlaylistLength()));

        mLastStatus = status;

        mBitrate.setText(status.getBitrate() + getResources().getString(R.string.bitrate_unit_kilo_bits));

        // Set audio properties string
        String properties = status.getSamplerate() + getResources().getString(R.string.samplerate_unit_hertz) + ' ';
        properties += status.getBitDepth() + getResources().getString(R.string.bitcount_unit) + ' ';
        properties += status.getChannelCount() + getResources().getString(R.string.channel_count_unit);
        mAudioProperties.setText(properties);
    }

    private void updateMPDCurrentTrack(MPDFile track) {
        mTrackName.setText(track.getTrackTitle());
        mTrackAdditionalInfo.setText(track.getTrackArtist() + getResources().getString(R.string.track_item_separator) + track.getTrackAlbum());

        // Calculate the margin to avoid cut off textviews
        RelativeLayout.LayoutParams layoutParams = (RelativeLayout.LayoutParams) mHeaderTextLayout.getLayoutParams();
        layoutParams.setMarginEnd((int) (mTopPlaylistButton.getWidth() * (1.0 - mDragOffset)));
        mHeaderTextLayout.setLayoutParams(layoutParams);

        mTrackURI.setText(track.getPath());
        if (track.getAlbumTrackCount() != 0) {
            mTrackNo.setText(String.valueOf(track.getTrackNumber()) + getResources().getString(R.string.track_number_album_count_separator) +
                    String.valueOf(track.getAlbumTrackCount()));
        } else {
            mTrackNo.setText(String.valueOf(track.getTrackNumber()));
        }

    }


    /**
     * Can be used to register an observer to this view, that is notified when a change of the dragstatus,offset happens.
     *
     * @param receiver Observer to register, only one observer at a time is possible.
     */
    public void registerDragStatusReceiver(NowPlayingDragStatusReceiver receiver) {
        mDragStatusReceiver = receiver;
        // Initial status notification
        if (mDragStatusReceiver != null) {

            // set drag status
            if (mDragOffset == 0.0f) {
                // top
                mDragStatusReceiver.onStatusChanged(NowPlayingDragStatusReceiver.DRAG_STATUS.DRAGGED_UP);
            } else {
                // bottom
                mDragStatusReceiver.onStatusChanged(NowPlayingDragStatusReceiver.DRAG_STATUS.DRAGGED_DOWN);
            }

            // set view status
            if (mViewSwitcher.getCurrentView() == mCoverImage) {
                // cover image is shown
                mDragStatusReceiver.onSwitchedViews(NowPlayingDragStatusReceiver.VIEW_SWITCHER_STATUS.COVER_VIEW);
            } else {
                // playlist view is shown
                mDragStatusReceiver.onSwitchedViews(NowPlayingDragStatusReceiver.VIEW_SWITCHER_STATUS.PLAYLIST_VIEW);
            }
        }
    }


    /**
     * Set the viewswitcher of cover/playlist view to the requested state.
     *
     * @param view the view which should be displayed.
     */
    public void setViewSwitcherStatus(NowPlayingDragStatusReceiver.VIEW_SWITCHER_STATUS view) {
        int color = 0;

        switch (view) {
            case COVER_VIEW:
                // change the view only if the requested view is not displayed
                if (mViewSwitcher.getCurrentView() != mCoverImage) {
                    mViewSwitcher.showNext();
                }
                color = ThemeUtils.getThemeColor(getContext(), android.R.attr.textColor);
                break;
            case PLAYLIST_VIEW:
                // change the view only if the requested view is not displayed
                if (mViewSwitcher.getCurrentView() != mPlaylistView) {
                    mViewSwitcher.showNext();
                }
                color = ThemeUtils.getThemeColor(getContext(), R.attr.colorAccent);
                break;
        }

        // tint the button according to the requested view
        mTopPlaylistButton.setImageTintList(ColorStateList.valueOf(color));
    }


    /**
     * Public interface used by observers to be notified about a change in drag state or drag position.
     */
    public interface NowPlayingDragStatusReceiver {
        // Possible values for DRAG_STATUS (up,down)
        enum DRAG_STATUS {
            DRAGGED_UP, DRAGGED_DOWN
        }

        // Possible values for the view in the viewswitcher (cover, playlist)
        enum VIEW_SWITCHER_STATUS {
            COVER_VIEW, PLAYLIST_VIEW
        }

        // Called when the whole view is either completely dragged up or down
        void onStatusChanged(DRAG_STATUS status);

        // Called continuously during dragging.
        void onDragPositionChanged(float pos);

        // Called when the view switcher switches between cover and playlist view
        void onSwitchedViews(VIEW_SWITCHER_STATUS view);

        // Called when the user starts the drag
        void onStartDrag();
    }

    private class ServerStatusListener extends MPDStatusChangeHandler {

        @Override
        protected void onNewStatusReady(MPDCurrentStatus status) {
            updateMPDStatus(status);
        }

        @Override
        protected void onNewTrackReady(MPDFile track) {
            updateMPDCurrentTrack(track);
        }
    }

    private class ServerConnectionListener extends MPDConnectionStateChangeHandler {

        @Override
        public void onConnected() {
            updateMPDStatus(MPDStateMonitoringHandler.getLastStatus());
        }

        @Override
        public void onDisconnected() {
            updateMPDStatus(new MPDCurrentStatus());
        }
    }

    private class PositionSeekbarListener implements SeekBar.OnSeekBarChangeListener {
        /**
         * Called if the user drags the seekbar to a new position or the seekbar is altered from
         * outside. Just do some seeking, if the action is done by the user.
         *
         * @param seekBar  Seekbar of which the progress was changed.
         * @param progress The new position of the seekbar.
         * @param fromUser If the action was initiated by the user.
         */
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (fromUser) {
                // FIXME Check if it is better to just update if user releases the seekbar
                // (network stress)
                MPDCommandHandler.seekSeconds(progress);
            }
        }

        /**
         * Called if the user starts moving the seekbar. We do not handle this for now.
         *
         * @param seekBar SeekBar that is used for dragging.
         */
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            // TODO Auto-generated method stub
        }

        /**
         * Called if the user ends moving the seekbar. We do not handle this for now.
         *
         * @param seekBar SeekBar that is used for dragging.
         */
        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            // TODO Auto-generated method stub
        }
    }

    private class VolumeSeekBarListener implements SeekBar.OnSeekBarChangeListener {
        /**
         * Called if the user drags the seekbar to a new position or the seekbar is altered from
         * outside. Just do some seeking, if the action is done by the user.
         *
         * @param seekBar  Seekbar of which the progress was changed.
         * @param progress The new position of the seekbar.
         * @param fromUser If the action was initiated by the user.
         */
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (fromUser) {
                // FIXME Check if it is better to just update if user releases the seekbar
                // (network stress)
                MPDCommandHandler.setVolume(progress);
            }
        }

        /**
         * Called if the user starts moving the seekbar. We do not handle this for now.
         *
         * @param seekBar SeekBar that is used for dragging.
         */
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            // TODO Auto-generated method stub
        }

        /**
         * Called if the user ends moving the seekbar. We do not handle this for now.
         *
         * @param seekBar SeekBar that is used for dragging.
         */
        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            // TODO Auto-generated method stub
        }
    }

}