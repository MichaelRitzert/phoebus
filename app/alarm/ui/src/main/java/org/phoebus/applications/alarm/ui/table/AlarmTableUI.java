/*******************************************************************************
 * Copyright (c) 2018 Oak Ridge National Laboratory.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.phoebus.applications.alarm.ui.table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.phoebus.applications.alarm.AlarmSystem;
import org.phoebus.applications.alarm.client.AlarmClient;
import org.phoebus.applications.alarm.model.AlarmTreeItem;
import org.phoebus.applications.alarm.model.SeverityLevel;
import org.phoebus.applications.alarm.ui.AlarmContextMenuHelper;
import org.phoebus.applications.alarm.ui.AlarmUI;
import org.phoebus.applications.alarm.ui.tree.ConfigureComponentAction;
import org.phoebus.applications.email.actions.SendEmailAction;
import org.phoebus.framework.jobs.JobManager;
import org.phoebus.framework.persistence.Memento;
import org.phoebus.logbook.ui.menu.SendLogbookAction;
import org.phoebus.ui.application.SaveSnapshotAction;
import org.phoebus.ui.javafx.ClearingTextField;
import org.phoebus.ui.javafx.ImageCache;
import org.phoebus.ui.javafx.PrintAction;
import org.phoebus.ui.javafx.Screenshot;
import org.phoebus.ui.javafx.ToolbarHelper;
import org.phoebus.ui.text.RegExHelper;
import org.phoebus.util.time.TimestampFormats;

import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableColumn.SortType;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

/** Alarm Table UI
 *
 *  <p>Show list of active and acknowledged alarms.
 *
 *  <p>While implemented as {@link BorderPane},
 *  only the methods defined in here should be called
 *  to interact with it.
 *
 *  @author Kay Kasemir
 */
@SuppressWarnings("nls")
public class AlarmTableUI extends BorderPane
{
    private final AlarmClient client;

    // Sorting:
    //
    // TableView supports sorting as a default when user clicks on columns.
    //
    // LinkedColumnSorter updates the requested sort in the 'other' table.
    // Wrapping the raw data into a SortedList persists the sort order
    // when elements are added/removed in the original data.
    // https://stackoverflow.com/questions/34889111/how-to-sort-a-tableview-programmatically
    //
    // Adding a callback to the observableArrayList instructs the list to also
    // trigger a re-sort when properties of the existing rows change.
    // https://rterp.wordpress.com/2015/05/08/automatically-sort-a-javafx-tableview/
    private final ObservableList<AlarmInfoRow> active_rows = FXCollections.observableArrayList(AlarmInfoRow.CHANGING_PROPERTIES);
    private final ObservableList<AlarmInfoRow> acknowledged_rows = FXCollections.observableArrayList(AlarmInfoRow.CHANGING_PROPERTIES);

    private final SplitPane split;

    private final Label active_count = new Label("Active Alarms");
    private final Label acknowledged_count = new Label("Acknowledged Alarms");

    private final TableView<AlarmInfoRow> active = createTable(active_rows, true);
    private final TableView<AlarmInfoRow> acknowledged = createTable(acknowledged_rows, false);

    final TextField search = new ClearingTextField();

    private final Label no_server = AlarmUI.createNoServerLabel();

    private final Button server_mode = new Button();

    private ToolBar toolbar = createToolbar();

    /** Table cell that shows a Severity as Icon */
    private class SeverityIconCell extends TableCell<AlarmInfoRow, SeverityLevel>
    {
        @Override
        protected void updateItem(final SeverityLevel item, final boolean empty)
        {
            super.updateItem(item, empty);

            if (empty  ||  item == null)
                setGraphic(null);
            else
                setGraphic(new ImageView(AlarmUI.getIcon(item)));
        }
    }

    /** Table cell that shows a Severity as text */
    private class SeverityLevelCell extends TableCell<AlarmInfoRow, SeverityLevel>
    {
        @Override
        protected void updateItem(final SeverityLevel item, final boolean empty)
        {
            super.updateItem(item, empty);

            if (empty  ||  item == null)
            {
                setText("");
                setTextFill(Color.BLACK);
            }
            else
            {
                setText(item.toString());
                setTextFill(AlarmUI.getColor(item));
            }
        }
    }

    /** Table cell that shows a time stamp */
    private class TimeCell extends TableCell<AlarmInfoRow, Instant>
    {
        @Override
        protected void updateItem(final Instant item, final boolean empty)
        {
            super.updateItem(item, empty);

            if (empty  ||  item == null)
                setText("");
            else
                setText(TimestampFormats.MILLI_FORMAT.format(item));
        }
    }

    public AlarmTableUI(final AlarmClient client)
    {
        this.client = client;

        // When user resizes columns, update them in the 'other' table
        active.setColumnResizePolicy(new LinkedColumnResize(active, acknowledged));
        acknowledged.setColumnResizePolicy(new LinkedColumnResize(acknowledged, active));

        // When user sorts column, apply the same to the 'other' table
        active.getSortOrder().addListener(new LinkedColumnSorter(active, acknowledged));
        acknowledged.getSortOrder().addListener(new LinkedColumnSorter(acknowledged, active));

        // Insets make ack. count appear similar to the active count,
        // which is laid out based on the ack/unack/search buttons in the toolbar
        acknowledged_count.setPadding(new Insets(10, 0, 10, 5));
        VBox.setVgrow(acknowledged, Priority.ALWAYS);
        final VBox bottom = new VBox(acknowledged_count, acknowledged);

        // Overall layout:
        // Toolbar
        // Top section of split: Active alarms
        // Bottom section o. s.: Ack'ed alarms
        split = new SplitPane(active, bottom);
        split.setOrientation(Orientation.VERTICAL);

        setTop(toolbar);
        setCenter(split);
    }

    ToolBar getToolbar()
    {
        return toolbar;
    }

    private ToolBar createToolbar()
    {
        setMaintenanceMode(false);
        server_mode.setOnAction(event ->  client.setMode(! client.isMaintenanceMode()));

        final Button acknowledge = new Button("", ImageCache.getImageView(AlarmUI.class, "/icons/acknowledge.png"));
        acknowledge.disableProperty().bind(Bindings.isEmpty(active.getSelectionModel().getSelectedItems()));
        acknowledge.setOnAction(event ->
        {
            for (AlarmInfoRow row : active.getSelectionModel().getSelectedItems())
                JobManager.schedule("ack", monitor -> client.acknowledge(row.item, true));
        });

        final Button unacknowledge = new Button("", ImageCache.getImageView(AlarmUI.class, "/icons/unacknowledge.png"));
        unacknowledge.disableProperty().bind(Bindings.isEmpty(acknowledged.getSelectionModel().getSelectedItems()));
        unacknowledge.setOnAction(event ->
        {
            for (AlarmInfoRow row : acknowledged.getSelectionModel().getSelectedItems())
                JobManager.schedule("unack", monitor -> client.acknowledge(row.item, false));
        });

        search.setTooltip(new Tooltip("Enter pattern ('vac', 'amp*trip')\nfor PV Name or Description,\npress RETURN to select"));
        search.textProperty().addListener(prop -> selectRows());

        return new ToolBar(active_count,ToolbarHelper.createStrut(), ToolbarHelper.createSpring(), server_mode, acknowledge, unacknowledge, search);
    }

    /** Show if connected to server or not
     *  @param alive Is server alive?
     */
    void setServerState(final boolean alive)
    {
        final ObservableList<Node> items = toolbar.getItems();
        items.remove(no_server);
        if (! alive)
            // Place to the left of spring, maint, ack, unack, filter,
            // i.e. right of "active alarms" and optional AlarmConfigSelector
            items.add(items.size() - 5, no_server);
    }

    void setMaintenanceMode(final boolean maintenance_mode)
    {
        if (maintenance_mode)
        {
            server_mode.setGraphic(ImageCache.getImageView(AlarmUI.class, "/icons/maintenance_mode.png"));
            server_mode.setTooltip(new Tooltip("Maintenance Mode\nINVALID alarms are not annunciated and automatically acknowledged.\nPress to return to Normal Mode"));
        }
        else
        {
            server_mode.setGraphic(ImageCache.getImageView(AlarmUI.class, "/icons/normal_mode.png"));
            server_mode.setTooltip(new Tooltip("Enable maintenance mode?\n\nIn maintenance mode, INVALID alarms are not annunciated;\nthey are automatically acknowledged.\nThis is meant to reduce the impact of alarm from IOC reboots\nor systems that are turned off for maintenance."));

        }
    }

    private TableView<AlarmInfoRow> createTable(final ObservableList<AlarmInfoRow> rows,
                                                final boolean active)
    {
        final SortedList<AlarmInfoRow> sorted = new SortedList<>(rows);
        final TableView<AlarmInfoRow> table = new TableView<>(sorted);

        // Ensure that the sorted rows are always updated as the column sorting
        // of the TableView is changed by the user clicking on table headers.
        sorted.comparatorProperty().bind(table.comparatorProperty());

        TableColumn<AlarmInfoRow, SeverityLevel> sevcol = new TableColumn<>(/* Icon */);
        sevcol.setPrefWidth(25);
        sevcol.setReorderable(false);
        sevcol.setResizable(false);
        sevcol.setCellValueFactory(cell -> cell.getValue().severity);
        sevcol.setCellFactory(c -> new SeverityIconCell());
        table.getColumns().add(sevcol);

        TableColumn<AlarmInfoRow, String> col = new TableColumn<>("PV");
        col.setPrefWidth(240);
        col.setReorderable(false);
        col.setCellValueFactory(cell -> cell.getValue().pv);

        table.getColumns().add(col);

        col = new TableColumn<>("Description");
        col.setPrefWidth(400);
        col.setReorderable(false);
        col.setCellValueFactory(cell -> cell.getValue().description);
        table.getColumns().add(col);

        sevcol = new TableColumn<>("Alarm Severity");
        sevcol.setPrefWidth(130);
        sevcol.setReorderable(false);
        sevcol.setCellValueFactory(cell -> cell.getValue().severity);
        sevcol.setCellFactory(c -> new SeverityLevelCell());
        table.getColumns().add(sevcol);

        col = new TableColumn<>("Alarm Status");
        col.setPrefWidth(130);
        col.setReorderable(false);
        col.setCellValueFactory(cell -> cell.getValue().status);
        table.getColumns().add(col);

        TableColumn<AlarmInfoRow, Instant> timecol = new TableColumn<>("Alarm Time");
        timecol.setPrefWidth(200);
        timecol.setReorderable(false);
        timecol.setCellValueFactory(cell -> cell.getValue().time);
        timecol.setCellFactory(c -> new TimeCell());
        table.getColumns().add(timecol);

        col = new TableColumn<>("Alarm Value");
        col.setPrefWidth(100);
        col.setReorderable(false);
        col.setCellValueFactory(cell -> cell.getValue().value);
        table.getColumns().add(col);

        sevcol = new TableColumn<>("PV Severity");
        sevcol.setPrefWidth(130);
        sevcol.setReorderable(false);
        sevcol.setCellValueFactory(cell -> cell.getValue().pv_severity);
        sevcol.setCellFactory(c -> new SeverityLevelCell());
        table.getColumns().add(sevcol);

        col = new TableColumn<>("PV Status");
        col.setPrefWidth(130);
        col.setReorderable(false);
        col.setCellValueFactory(cell -> cell.getValue().pv_status);
        table.getColumns().add(col);

        table.setPlaceholder(new Label(active ? "No active alarms" : "No acknowledged alarms"));
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        createContextMenu(table, active);

        // Double-click to acknowledge or un-acknowledge
        table.setRowFactory(tv ->
        {
            final TableRow<AlarmInfoRow> row = new TableRow<>();
            row.setOnMouseClicked(event ->
            {
                if (event.getClickCount() == 2  &&  !row.isEmpty())
                    JobManager.schedule("ack", monitor ->  client.acknowledge(row.getItem().item, active));
            });
            return row;
        });

        // Drag selected PV names
        table.setOnDragDetected(event ->
        {
            final Dragboard db = table.startDragAndDrop(TransferMode.COPY);
            final ClipboardContent content = new ClipboardContent();
            final StringBuilder buf = new StringBuilder();
            for (AlarmInfoRow row : table.getSelectionModel().getSelectedItems())
            {
                if (buf.length() > 0)
                    buf.append(" ");
                buf.append(row.pv.get());
            }
            content.putString(buf.toString());
            db.setContent(content);
            event.consume();
        });

        return table;
    }

    private void createContextMenu(final TableView<AlarmInfoRow> table, final boolean active)
    {
        final ContextMenu menu = new ContextMenu();
        table.setOnContextMenuRequested(event ->
        {
            final ObservableList<MenuItem> menu_items = menu.getItems();
            menu_items.clear();

            final List<AlarmTreeItem<?>> selection = new ArrayList<>();
            for (AlarmInfoRow row : table.getSelectionModel().getSelectedItems())
                selection.add(row.item);

            // Add guidance etc.
            new AlarmContextMenuHelper().addSupportedEntries(table, client, menu, selection);
            if (menu_items.size() > 0)
                menu_items.add(new SeparatorMenuItem());

            if (AlarmUI.mayConfigure()  &&   selection.size() == 1)
            {
                menu_items.add(new ConfigureComponentAction(table, client, selection.get(0)));
                menu_items.add(new SeparatorMenuItem());
            }
            menu_items.add(new PrintAction(this));
            menu_items.add(new SaveSnapshotAction(table));
            menu_items.add(new SendEmailAction(table, "Alarm Snapshot", this::list_alarms, () -> Screenshot.imageFromNode(this)));
            menu_items.add(new SendLogbookAction(table, "Alarm Snapshot", this::list_alarms, () -> Screenshot.imageFromNode(this)));

            menu.show(table.getScene().getWindow(), event.getScreenX(), event.getScreenY());
        });
    }

    private String list_alarms()
    {
        final StringBuilder buf = new StringBuilder();

        buf.append("Active Alarms\n");
        buf.append("=============\n");
        for (AlarmInfoRow row : active_rows)
            buf.append(row).append("\n");

        buf.append("Acknowledged Alarms\n");
        buf.append("===================\n");
        for (AlarmInfoRow row : active_rows)
            buf.append(row).append("\n");

        return buf.toString();
    }

    void restore(final Memento memento)
    {
        memento.getNumber("POS").ifPresent(pos -> split.setDividerPositions(pos.doubleValue()));

        int i = 0;
        for (TableColumn<AlarmInfoRow, ?> col : active.getColumns())
            memento.getNumber("COL" + i++).ifPresent(wid -> col.setPrefWidth(wid.doubleValue()));

        i = memento.getNumber("SORT").orElse(-1).intValue();
        if (i >= 0)
        {
            final TableColumn<AlarmInfoRow, ?> col = active.getColumns().get(i);
            active.getSortOrder().setAll(List.of(col));
            memento.getNumber("DIR").ifPresent(dir -> col.setSortType(SortType.values()[dir.intValue()]));
        }
    }

    void save(final Memento memento)
    {
        memento.setNumber("POS", split.getDividers().get(0).getPosition());

        int i = 0;
        for (TableColumn<AlarmInfoRow, ?> col : active.getColumns())
            memento.setNumber("COL" + i++, col.getWidth());

        final List<TableColumn<AlarmInfoRow, ?>> sorted = active.getSortOrder();
        if (sorted.size() == 1)
        {
            i = active.getColumns().indexOf(sorted.get(0));
            memento.setNumber("SORT", i);
            memento.setNumber("DIR", active.getColumns().get(i).getSortType().ordinal());
        }
    }

    /** Update the alarm information to show
     *
     *  <p>The provided lists are not retained, but
     *  some of the AlarmInfoRow items may be added
     *  to the table's list of items.
     *
     *  @param active Active alarms
     *  @param acknowledged Acknowledged alarms
     */
    public void update(final List<AlarmInfoRow> active,
                       final List<AlarmInfoRow> acknowledged)
    {
        limitAlarmCount(active, active_count, "Active Alarms: ");
        limitAlarmCount(acknowledged, acknowledged_count, "Acknowledged Alarms: ");
        update(active_rows, active);
        update(acknowledged_rows, acknowledged);
    }

    /** Limit the number of alarms
     *  @param alarms List of alarms, may be trimmed
     *  @param alarm_count Label where count will be shown
     *  @param message Message to use for the count
     */
    private void limitAlarmCount(final List<AlarmInfoRow> alarms,
                                 final Label alarm_count, final String message)
    {
        final int N = alarms.size();
        final StringBuilder buf = new StringBuilder();
        buf.append(message).append(N);
        if (N > AlarmSystem.alarm_table_max_rows)
        {
            buf.append(" (").append(N - AlarmSystem.alarm_table_max_rows).append(" not shown)");
            alarms.subList(AlarmSystem.alarm_table_max_rows, N).clear();
        }
        alarm_count.setText(buf.toString());
    }

    /** Update existing list of items with new input
     *  @param items List to update
     *  @param input New input
     */
    private void update(final ObservableList<AlarmInfoRow> items, final List<AlarmInfoRow> input)
    {
        // Similar, but might trigger a full table redraw:
        // items.setAll(input);

        // Update content of common list entries
        int N = Math.min(items.size(), input.size());
        for (int i=0; i<N; ++i)
            items.get(i).copy(input.get(i));

        N = input.size();
        if (N > items.size())
        {
            // Additional elements if input is larger that existing list
            for (int i=items.size(); i<N; ++i)
                items.add(input.get(i));
        }
        else // Trim items, input has fewer elements
            items.remove(N, items.size());

        selectRows();
    }

    /** Select all rows that match the current 'search' pattern */
    private void selectRows()
    {
        final String glob = search.getText().trim();
        if (glob.isEmpty())
        {
            active.getSelectionModel().clearSelection();
            acknowledged.getSelectionModel().clearSelection();
            return;
        }

        final Pattern pattern = Pattern.compile(RegExHelper.fullRegexFromGlob(glob),
                                                Pattern.CASE_INSENSITIVE);
        selectRows(active, pattern);
        selectRows(acknowledged, pattern);
    }

    private void selectRows(final TableView<AlarmInfoRow> table, final Pattern pattern)
    {
        table.getSelectionModel().clearSelection();

        int i = 0;
        for (AlarmInfoRow row : table.getItems())
        {
            if (pattern.matcher(row.pv.get()).matches()  ||
                pattern.matcher(row.description.get()).matches())
                table.getSelectionModel().select(i);
            ++i;
        }
    }
}
