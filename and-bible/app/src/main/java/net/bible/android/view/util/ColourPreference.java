package net.bible.android.view.util;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.preference.ListPreference;
import android.util.AttributeSet;
import android.widget.ListAdapter;
import android.widget.ListView;

/**
 * @author Martin Denham [mjdenham at gmail dot com]
 * @see gnu.lgpl.License for license details.<br>
 * The copyright to this program is held by it's author.
 */
public class ColourPreference extends ListPreference {

	public ColourPreference(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public ColourPreference(Context context) {
		super(context);
	}

//	@Override protected void onPrepareDialogBuilder(AlertDialog.Builder builder) {
//		super.onPrepareDialogBuilder(builder);
//		// do something with builder and make a nice cute dialogue, for example, like this
//		builder.setSingleChoiceItems(new CustomListPreferenceAdapter, 0, new DialogInterface.OnClickListener() {
//			public void onClick(DialogInterface dialog, int which) {
////				mClickedDialogEntryIndex = which;
//
//				/*
//				 * Clicking on an item simulates the positive button
//				 * click, and dismisses the dialog.
//				 */
//				ColourPreference.this.onClick(dialog, DialogInterface.BUTTON_POSITIVE);
//				dialog.dismiss();
//			});
//	}
	@Override
	protected void showDialog(Bundle state) {
		super.showDialog(state);
		AlertDialog dialog = (AlertDialog) getDialog();
		ListView listView = dialog.getListView();
		ListAdapter adapter = listView.getAdapter();
		final ListPrefWrapperAdapter fontTypeAdapter = createWrapperAdapter(adapter);

		// Adjust the selection because resetting the adapter loses the selection.
		int selectedPosition = findIndexOfValue(getValue());
		listView.setAdapter(fontTypeAdapter);
		if (selectedPosition != -1) {
			listView.setItemChecked(selectedPosition, true);
			listView.setSelection(selectedPosition);
		}
	}

	protected ListPrefWrapperAdapter createWrapperAdapter(ListAdapter origAdapter) {
		return new ListPrefWrapperAdapter(origAdapter);
	}
}