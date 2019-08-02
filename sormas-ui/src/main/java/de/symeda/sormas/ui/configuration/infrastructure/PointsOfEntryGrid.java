package de.symeda.sormas.ui.configuration.infrastructure;

import java.util.stream.Collectors;

import com.vaadin.data.provider.DataProvider;
import com.vaadin.icons.VaadinIcons;
import com.vaadin.shared.data.sort.SortDirection;
import com.vaadin.ui.renderers.HtmlRenderer;

import de.symeda.sormas.api.FacadeProvider;
import de.symeda.sormas.api.i18n.I18nProperties;
import de.symeda.sormas.api.infrastructure.PointOfEntryCriteria;
import de.symeda.sormas.api.infrastructure.PointOfEntryDto;
import de.symeda.sormas.api.user.UserRight;
import de.symeda.sormas.api.utils.SortProperty;
import de.symeda.sormas.ui.ControllerProvider;
import de.symeda.sormas.ui.UserProvider;
import de.symeda.sormas.ui.utils.BooleanRenderer;
import de.symeda.sormas.ui.utils.FilteredGrid;

public class PointsOfEntryGrid extends FilteredGrid<PointOfEntryDto, PointOfEntryCriteria> {

	private static final long serialVersionUID = -650914769265620769L;
	
	public static final String EDIT_BTN_ID = "edit";

	public PointsOfEntryGrid() {
		super(PointOfEntryDto.class);
		setSizeFull();
		setSelectionMode(SelectionMode.NONE);
		
		DataProvider<PointOfEntryDto, PointOfEntryCriteria> dataProvider = DataProvider.fromFilteringCallbacks(
				query -> FacadeProvider.getPointOfEntryFacade().getIndexList(
						query.getFilter().orElse(null), query.getOffset(), query.getLimit(),
						query.getSortOrders().stream().map(sortOrder -> new SortProperty(sortOrder.getSorted(), sortOrder.getDirection() == SortDirection.ASCENDING))
						.collect(Collectors.toList())).stream(),
				query -> {
					return (int) FacadeProvider.getPointOfEntryFacade().count(query.getFilter().orElse(null));
				});
		setDataProvider(dataProvider);
		
		setColumns(PointOfEntryDto.NAME, PointOfEntryDto.POINT_OF_ENTRY_TYPE, PointOfEntryDto.REGION, PointOfEntryDto.DISTRICT, PointOfEntryDto.LATITUDE, PointOfEntryDto.LONGITUDE, PointOfEntryDto.ACTIVE);
		
		if (UserProvider.getCurrent().hasUserRight(UserRight.INFRASTRUCTURE_EDIT)) {
			Column<PointOfEntryDto, String> editColumn = addColumn(entry -> VaadinIcons.EDIT.getHtml(), new HtmlRenderer());
			editColumn.setId(EDIT_BTN_ID);
			editColumn.setWidth(20);

			addItemClickListener(e -> {
				if (e.getColumn() != null && (EDIT_BTN_ID.equals(e.getColumn().getId()) || e.getMouseEventDetails().isDoubleClick())) {
					ControllerProvider.getInfrastructureController().editPointOfEntry(e.getItem().getUuid());
				}
			});
		}
		
		for (Column<?, ?> column : getColumns()) {
			column.setCaption(I18nProperties.getPrefixCaption(
					PointOfEntryDto.I18N_PREFIX, column.getId().toString(), column.getCaption()));
		}
		
		getColumn(PointOfEntryDto.ACTIVE).setRenderer(new BooleanRenderer());
	}

	public void reload() {
		getDataProvider().refreshAll();
	}

}