package me.devsaki.hentoid.views;

import android.animation.Animator;
import android.content.Context;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.List;

import eu.davidea.flexibleadapter.FlexibleAdapter;
import eu.davidea.flexibleadapter.helpers.AnimatorHelper;
import eu.davidea.flexibleadapter.items.AbstractFlexibleItem;
import eu.davidea.viewholders.FlexibleViewHolder;
import me.devsaki.hentoid.R;

import static eu.davidea.flexibleadapter.Payload.NO_MORE_LOAD;

public class ProgressItem extends AbstractFlexibleItem<ProgressItem.ProgressViewHolder> {

    private StatusEnum status = StatusEnum.MORE_TO_LOAD;

    @Override
    public boolean equals(Object o) {
        return this == o;//The default implementation
    }

    public StatusEnum getStatus() {
        return status;
    }

    public void setStatus(StatusEnum status) {
        this.status = status;
    }

    @Override
    public int getLayoutRes() {
        return R.layout.item_library_progress;
    }

    @Override
    public ProgressViewHolder createViewHolder(View view, FlexibleAdapter adapter) {
        return new ProgressViewHolder(view, adapter);
    }

    @Override
    public void bindViewHolder(FlexibleAdapter adapter, ProgressViewHolder holder,
                               int position, List payloads) {

        Context context = holder.itemView.getContext();
        holder.progressBar.setVisibility(View.GONE);
        holder.progressMessage.setVisibility(View.VISIBLE);

        if (!adapter.isEndlessScrollEnabled()) {
            setStatus(StatusEnum.DISABLE_ENDLESS);
        } else if (payloads.contains(NO_MORE_LOAD)) {
            setStatus(StatusEnum.NO_MORE_LOAD);
        }

        switch (this.status) {
            case NO_MORE_LOAD:
                holder.progressMessage.setText(
                        context.getString(R.string.no_more_load_retry));
                // Reset to default status for next binding
                setStatus(StatusEnum.MORE_TO_LOAD);
                break;
            case DISABLE_ENDLESS:
                holder.progressMessage.setText(context.getString(R.string.endless_disabled));
                break;
            case ON_CANCEL:
                holder.progressMessage.setText(context.getString(R.string.endless_cancel));
                // Reset to default status for next binding
                setStatus(StatusEnum.MORE_TO_LOAD);
                break;
            case ON_ERROR:
                holder.progressMessage.setText(context.getString(R.string.endless_error));
                // Reset to default status for next binding
                setStatus(StatusEnum.MORE_TO_LOAD);
                break;
            default:
                holder.progressBar.setVisibility(View.VISIBLE);
                holder.progressMessage.setVisibility(View.GONE);
                break;
        }
    }

    static class ProgressViewHolder extends FlexibleViewHolder {
        ProgressBar progressBar;
        TextView progressMessage;

        ProgressViewHolder(View view, FlexibleAdapter adapter) {
            super(view, adapter);
            progressBar = view.findViewById(R.id.progress_bar);
            progressMessage = view.findViewById(R.id.progress_message);
        }

        @Override
        public void scrollAnimators(@NonNull List<Animator> animators, int position, boolean isForward) {
            AnimatorHelper.scaleAnimator(animators, itemView, 0f);
        }
    }

    public enum StatusEnum {
        MORE_TO_LOAD, //Default = should have an empty Payload
        DISABLE_ENDLESS, //Endless is disabled because user has set limits
        NO_MORE_LOAD, //Non-empty Payload = Payload.NO_MORE_LOAD
        ON_CANCEL,
        ON_ERROR
    }
}