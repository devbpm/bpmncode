/*
 * Visor bpmn.io (solo lectura) del BPMN 2.0 que genera bpmncode a partir del código Java anotado.
 * El navegador solo representa el XML recibido; nunca define ni modifica un proceso.
 */
(function () {
	'use strict';

	var params = new URLSearchParams(location.search);
	// El idioma de la página que contiene el visor viaja en ?lang=; si falta, el servidor usa el de la sesión.
	var langQuery = params.get('lang') ? '?lang=' + encodeURIComponent(params.get('lang')) : '';
	// La página la sirve la librería con la dirección de su API en data-api (ruta de contexto y ruta base incluidas).
	var base = document.body.getAttribute('data-api');
	var api = base + '/views';
	var requestedElement = params.get('element');

	var select = document.getElementById('view');
	var status = document.getElementById('status');
	var download = document.getElementById('download');
	var details = document.getElementById('details');
	var picker = document.getElementById('element');
	var canvasContainer = document.getElementById('canvas');
	var toolbar = document.getElementById('id-toolbar');
	var idButtons = Array.prototype.slice.call(toolbar.querySelectorAll('button[data-kind]'));

	var viewer = new BpmnJS({ container: canvasContainer });
	var canvas = viewer.get('canvas');
	var overlays = viewer.get('overlays');
	var elementRegistry = viewer.get('elementRegistry');

	var messages = {};

	// --- Textos ---------------------------------------------------------------------------------------------------

	function text(key, args) {
		var pattern = messages[key] || key;
		return (args || []).reduce(function (result, value, index) {
			return result.split('{' + index + '}').join(String(value));
		}, pattern);
	}

	function applyMessages() {
		document.querySelectorAll('[data-i18n]').forEach(function (element) {
			var key = element.getAttribute('data-i18n');
			if (messages[key]) {
				element.textContent = messages[key];
			}
		});
		document.querySelectorAll('[data-i18n-aria]').forEach(function (element) {
			var key = element.getAttribute('data-i18n-aria');
			if (messages[key]) {
				element.setAttribute('aria-label', messages[key]);
			}
		});
		if (messages.title) {
			document.title = messages.title;
		}
	}

	// --- Carga de diagramas ---------------------------------------------------------------------------------------

	function checked(response) {
		if (!response.ok) {
			throw new Error(response.status + ' ' + response.statusText);
		}
		return response;
	}

	function fail(error) {
		status.textContent = text('error', [error.message]);
		status.classList.add('error');
	}

	function fit() {
		canvas.zoom('fit-viewport', 'auto');
	}


	/**
	 * El dibujo es una imagen con nombre (role="img" + aria-label en el svg). El visor es de solo lectura, así que el
	 * svg no necesita foco: se le quita el tabindex que añade bpmn-js para que la imagen no sea un control (4.1.2).
	 */
	function describeDiagram(label) {
		var svg = canvasContainer.querySelector('svg');
		if (svg) {
			svg.removeAttribute('tabindex');
			svg.setAttribute('role', 'img');
			svg.setAttribute('aria-label', label);
		}
	}
	function show(viewId) {
		var url = api + '/' + encodeURIComponent(viewId) + langQuery;
		status.classList.remove('error');
		status.textContent = text('loading');
		details.hidden = true;
		selectedId = null;
		// Cada diagrama se abre sin la capa de calor anterior: la vuelve a pintar loadHeatmap si toca.
		removeHeatLayer();
		hideHeatTip();
		heatData = {};
		return fetch(url)
			.then(checked)
			.then(function (response) {
				var language = response.headers.get('Content-Language');
				if (language) {
					document.documentElement.lang = language;
				}
				return response.text();
			})
			.then(function (xml) { return viewer.importXML(xml); })
			.then(function (result) {
				// Cada diagrama se abre con todos los identificadores ocultos; el estado no se conserva.
				resetIdButtons();
				fit();
				var label = select.options[select.selectedIndex] ? select.options[select.selectedIndex].text : viewId;
				canvasContainer.setAttribute('aria-label', text('canvas', [label]));
				describeDiagram(text('canvas', [label]));
				download.href = url;
				download.download = viewId + '.bpmn';
				var query = '?view=' + encodeURIComponent(viewId) + (params.get('lang') ? '&lang=' + params.get('lang') : '')
						+ (pathInstance ? '&instance=' + encodeURIComponent(pathInstance) : '') + (heatMode ? '&heatmap=1' : '');
				history.replaceState(null, '', query);
				status.textContent = result.warnings.length
					? text('warnings', [result.warnings.length])
					: text('loaded', [viewId]);
				return loadElements(viewId);
			})
			.then(function () {
				// En modo mapa de calor, cada vista de proceso se colorea con el histórico de su versión.
				return heatMode ? loadHeatmap(viewId) : null;
			})
			.then(function () {
				// ?element= abre el diagrama con un elemento seleccionado (p. ej. desde una pantalla de la aplicación).
				if (requestedElement) {
					selectElement(requestedElement);
					requestedElement = null;
				}
			});
	}

	// --- Barra «Mostrar ID» ---------------------------------------------------------------------------------------

	var ID_TYPES = { task: 'bpmn:Task', event: 'bpmn:Event', gateway: 'bpmn:Gateway' };

	function activeKinds() {
		return idButtons
			.filter(function (button) { return button.getAttribute('aria-pressed') === 'true'; })
			.map(function (button) { return button.getAttribute('data-kind'); });
	}

	function kindOf(element, kinds) {
		var businessObject = element.businessObject;
		for (var i = 0; i < kinds.length; i++) {
			if (businessObject.$instanceOf(ID_TYPES[kinds[i]])) {
				return kinds[i];
			}
		}
		return null;
	}

	/** Borra todas las insignias de identificador y vuelve a pintar las de los tipos activos (solo visual). */
	function paintIds() {
		overlays.remove({ type: 'id' });
		var kinds = activeKinds();
		if (!kinds.length) {
			return;
		}
		elementRegistry.forEach(function (element) {
			// Se ignoran las etiquetas, los flujos (tienen waypoints) y los elementos sin businessObject.
			if (element.type === 'label' || element.waypoints || !element.businessObject) {
				return;
			}
			var kind = kindOf(element, kinds);
			if (!kind) {
				return;
			}
			var below = kind === 'task';
			var badge = document.createElement('span');
			badge.className = 'badge text-bg-light border font-monospace id-badge ' + (below ? 'id-badge-below' : 'id-badge-above');
			// El identificador viene del propio fichero BPMN: siempre como texto, nunca como HTML.
			badge.textContent = element.businessObject.id;
			overlays.add(element, 'id', {
				// En las tareas el nombre va dentro de la figura: la insignia va debajo. En eventos y gateways el
				// nombre va debajo: la insignia va encima para no taparlo. En ambos casos, centrada.
				position: below
					? { top: element.height + 4, left: element.width / 2 }
					: { top: -4, left: element.width / 2 },
				html: badge
			});
		});
	}

	function setPressed(button, pressed) {
		button.setAttribute('aria-pressed', pressed ? 'true' : 'false');
		button.classList.toggle('active', pressed);
	}

	function resetIdButtons() {
		idButtons.forEach(function (button) { setPressed(button, false); });
		paintIds();
	}

	/** Índice móvil: solo un botón del grupo está en el orden de tabulación. */
	function makeTabbable(target) {
		idButtons.forEach(function (button) {
			button.tabIndex = button === target ? 0 : -1;
		});
	}

	idButtons.forEach(function (button) {
		button.addEventListener('click', function () {
			setPressed(button, button.getAttribute('aria-pressed') !== 'true');
			makeTabbable(button);
			paintIds();
		});
		button.addEventListener('focus', function () {
			makeTabbable(button);
		});
	});

	/** Destino del foco para cada tecla de navegación; el resto de teclas (incluido Tab) no se tocan. */
	var FOCUS_MOVES = {
		ArrowRight: function (index, count) { return (index + 1) % count; },
		ArrowLeft: function (index, count) { return (index - 1 + count) % count; },
		Home: function () { return 0; },
		End: function (index, count) { return count - 1; }
	};

	toolbar.addEventListener('keydown', function (event) {
		var index = idButtons.indexOf(document.activeElement);
		var move = Object.prototype.hasOwnProperty.call(FOCUS_MOVES, event.key) ? FOCUS_MOVES[event.key] : null;
		if (index < 0 || move === null) {
			return;
		}
		var next = move(index, idButtons.length);
		event.preventDefault();
		makeTabbable(idButtons[next]);
		idButtons[next].focus();
	});

	// --- Ficha del elemento ---------------------------------------------------------------------------------------

	// Fichas de la vista cargada, por identificador del BPMN: proceso, versión exacta y código de cada elemento.
	var infos = {};
	var selectedId = null;
	var GROUPS = ['node', 'flow', 'participant', 'message'];

	function create(tag, className, content) {
		var node = document.createElement(tag);
		if (className) {
			node.className = className;
		}
		if (content !== undefined && content !== null) {
			node.textContent = content;
		}
		return node;
	}

	function code(content) {
		return create('code', null, content);
	}

	/** Sección con título y una lista de definiciones [término, contenido]; omite las filas sin contenido. */
	function section(titleKey, rows) {
		var present = rows.filter(function (row) { return row[1] !== null && row[1] !== undefined && row[1] !== ''; });
		if (!present.length) {
			return null;
		}
		var wrapper = create('section', 'details-section');
		wrapper.appendChild(create('h3', null, text(titleKey)));
		var list = create('dl');
		present.forEach(function (row) {
			list.appendChild(create('dt', null, text(row[0])));
			var value = create('dd');
			value.appendChild(row[1] instanceof Node ? row[1] : document.createTextNode(String(row[1])));
			list.appendChild(value);
		});
		wrapper.appendChild(list);
		return wrapper;
	}

	function listSection(titleKey, items, render) {
		if (!items || !items.length) {
			return null;
		}
		var wrapper = create('section', 'details-section');
		wrapper.appendChild(create('h3', null, text(titleKey)));
		var list = create('ul');
		items.forEach(function (item) {
			var entry = create('li');
			render(item).forEach(function (part) {
				entry.appendChild(part instanceof Node ? part : document.createTextNode(part));
			});
			list.appendChild(entry);
		});
		wrapper.appendChild(list);
		return wrapper;
	}

	function versionBadge(process) {
		return create('span', 'details-badge ' + (process.current ? 'details-current' : 'details-previous'),
				process.versionLabel);
	}

	function processName(process) {
		var name = create('span');
		name.appendChild(document.createTextNode(process.name + ' '));
		name.appendChild(code(process.id));
		return name;
	}

	function render(info) {
		var body = document.getElementById('details-body');
		body.textContent = '';
		document.getElementById('details-kind').textContent = info.kind + ' · ' + info.id;
		document.getElementById('details-name').textContent = info.name;
		[
			section('details.process', [
				['details.processName', processName(info.process)],
				['details.version', versionBadge(info.process)],
				['details.definedBy', code(info.process.definedBy)]
			]),
			info.code && section('details.code', [
				['details.class', code(info.code.className)],
				['details.method', info.code.member ? code(info.code.member) : null]
			]),
			listSection('details.rules', info.rules, function (rule) {
				return [create('strong', null, rule.id + ' v' + rule.version), ' ' + rule.name, create('br'),
					code(rule.code.className + (rule.code.member ? '#' + rule.code.member : ''))];
			}),
			listSection('details.events', info.events, function (event) {
				return [event.direction + ': ', create('strong', null, event.id), ' ' + event.name, create('br'),
					code(event.type)];
			}),
			// Lo que dice el modelo de quién hace la tarea: un recurso declarado o una expresión que resuelve la aplicación.
			listSection('details.roles', info.roles, function (role) {
				return role.resourceId
					? [role.kind + ': ', create('strong', null, role.resourceId), ' ' + role.name, create('br'),
						code(role.type)]
					: [role.kind + ': ', code(role.name)];
			}),
			info.transition && section('details.transition', [
				['details.from', info.transition.source],
				['details.to', info.transition.target],
				['details.condition', info.transition.condition],
				['details.when', info.transition.when ? code(info.transition.when) : null]
			]),
			info.runningHere !== null && info.runningHere !== undefined && section('details.runtime', [
				['details.waiting', String(info.runningHere)]
			]),
			// En el mapa de calor, la ficha da las mismas cifras que el color (también a quien usa el teclado).
			heatData[info.id] && section('details.history', [
				['details.executions', String(heatData[info.id].executions)],
				['details.instancesPassing', heatData[info.id].instances + ' (' + heatData[info.id].share + ' %)']
			]),
			info.description ? create('p', 'details-description', info.description) : null
		].forEach(function (part) {
			if (part) {
				body.appendChild(part);
			}
		});
		details.hidden = false;
	}

	/** Selecciona un elemento: lo resalta en el dibujo, sincroniza el selector y muestra su ficha. */
	function selectElement(id) {
		if (selectedId && elementRegistry.get(selectedId)) {
			canvas.removeMarker(selectedId, 'bpmncode-selected');
		}
		selectedId = null;
		var info = infos[id];
		if (!info) {
			details.hidden = true;
			picker.value = '';
			hideHeatTip();
			return;
		}
		selectedId = id;
		var shape = elementRegistry.get(id);
		if (shape) {
			canvas.addMarker(id, 'bpmncode-selected');
			if (typeof canvas.scrollToElement === 'function') {
				canvas.scrollToElement(shape);
			}
		}
		picker.value = id;
		render(info);
		showHeatTip(id);
	}

	/** Llena el selector de elementos, agrupado por categoría y en el orden del modelo. */
	function fillPicker(list) {
		picker.textContent = '';
		picker.add(new Option(text('pickPlaceholder'), ''));
		GROUPS.forEach(function (category) {
			var members = list.filter(function (info) { return info.category === category; });
			if (!members.length) {
				return;
			}
			var group = document.createElement('optgroup');
			group.label = text('group.' + category);
			members.forEach(function (info) {
				group.appendChild(new Option(info.name + ' [' + info.id + ']', info.id));
			});
			picker.appendChild(group);
		});
	}

	function loadElements(viewId) {
		return fetch(api + '/' + encodeURIComponent(viewId) + '/elements' + langQuery)
			.then(checked)
			.then(function (response) { return response.json(); })
			.then(function (list) {
				infos = {};
				list.forEach(function (info) { infos[info.id] = info; });
				fillPicker(list);
			});
	}

	viewer.get('eventBus').on('element.click', function (event) {
		var target = event.element;
		// Una etiqueta pertenece a su elemento; el fondo del lienzo cierra la ficha.
		selectElement(target.labelTarget ? target.labelTarget.id : target.id);
	});
	picker.addEventListener('change', function () {
		selectElement(picker.value);
	});
	document.getElementById('close-details').addEventListener('click', function () {
		selectElement(null);
		picker.focus();
	});
	document.getElementById('fit').addEventListener('click', fit);
	select.addEventListener('change', function () {
		// El camino de una instancia solo tiene sentido sobre la vista de su versión.
		clearPath();
		show(select.value).catch(fail);
	});

	var resizeTimer;
	window.addEventListener('resize', function () {
		clearTimeout(resizeTimer);
		resizeTimer = setTimeout(function () {
			canvas.resized();
			fit();
		}, 150);
	});

	// --- Camino seguido por una instancia -------------------------------------------------------------------------

	// ?instance= abre la vista de la versión de la instancia y colorea el camino, como el Cockpit de Camunda: pasos
	// ejecutados con su orden, transiciones recorridas y paso en espera. El resumen en texto acompaña siempre al color.
	var pathInstance = params.get('instance');
	var pathLegend = document.getElementById('path-legend');

	function loadPath(instanceId) {
		return fetch(base + '/instances/' + encodeURIComponent(instanceId) + '/path' + langQuery)
			.then(checked)
			.then(function (response) { return response.json(); })
			.then(function (path) {
				select.value = path.viewId;
				return show(path.viewId).then(function () { paintPath(path); });
			});
	}

	function paintPath(path) {
		canvasContainer.classList.add('path-mode');
		var orders = {};
		path.steps.forEach(function (step) {
			(orders[step.nodeId] = orders[step.nodeId] || []).push(step.order);
		});
		Object.keys(orders).forEach(function (nodeId) {
			if (!elementRegistry.get(nodeId)) {
				return;
			}
			canvas.addMarker(nodeId, 'bpmncode-visited');
			overlays.add(nodeId, 'path', { position: { top: -11, left: -11 }, html: create('span', 'path-badge', orders[nodeId].join(', ')) });
		});
		path.flows.forEach(function (flowId) {
			if (elementRegistry.get(flowId)) {
				canvas.addMarker(flowId, 'bpmncode-taken');
			}
		});
		if (path.waitingNodeId && elementRegistry.get(path.waitingNodeId)) {
			canvas.addMarker(path.waitingNodeId, 'bpmncode-waiting');
			overlays.add(path.waitingNodeId, 'path', { position: { top: -13, right: 8 },
				html: create('span', 'path-badge path-badge-waiting', text('path.waitingBadge')) });
		}
		var last = path.steps.length ? path.steps[path.steps.length - 1].nodeName : '';
		document.getElementById('path-title').textContent = text('path.title',
				[path.instanceId.substring(0, 8), path.processId + ' v' + path.version, path.versionLabel]);
		document.getElementById('path-summary').textContent = path.running
			? text('path.running', [path.steps.length, path.waitingNodeName || last])
			: text('path.completed', [path.steps.length, last]);
		pathLegend.hidden = false;
		// La leyenda reduce el lienzo: se vuelve a ajustar el dibujo al espacio que queda.
		canvas.resized();
		fit();
	}

	function clearPath() {
		pathInstance = null;
		canvasContainer.classList.remove('path-mode');
		pathLegend.hidden = true;
		canvas.resized();
	}

	// --- Mapa de calor de una versión -------------------------------------------------------------------------------

	// ?heatmap=1 pinta sobre cada vista de proceso el histórico de su versión al estilo de Camunda Optimize: una capa de
	// calor continua bajo las figuras (azul en el halo, verde, amarillo y rojo donde más se ha ejecutado). Las cifras
	// están siempre a mano sin depender del color: al pasar el ratón, en la ficha del elemento (también con el selector
	// de teclado) y, si se activan, como insignias sobre cada paso.
	var heatMode = params.get('heatmap') === '1' && !pathInstance;
	var heatLegend = document.getElementById('heat-legend');
	var heatNumbersButton = document.getElementById('heat-numbers');
	var heatData = {};
	var heatImage = null;
	var heatTipId = null;

	// Paleta de heatmap.js (la que usa Optimize): posición en la escala 0..1 → color.
	var HEAT_GRADIENT = [[0.25, [0, 0, 255]], [0.55, [0, 255, 0]], [0.85, [255, 255, 0]], [1, [255, 0, 0]]];
	var HEAT_HALO = 32;            // difuminado alrededor de cada paso, en unidades del diagrama
	var HEAT_MAX_OPACITY = 0.8;
	var HEAT_MIN_INTENSITY = 0.2;  // un paso ejecutado una sola vez se ve igualmente (azul)

	function loadHeatmap(viewId) {
		return fetch(api + '/' + encodeURIComponent(viewId) + '/heatmap' + langQuery)
			.then(function (response) {
				if (response.status === 404) {
					// La colaboración mezcla procesos: no tiene mapa de calor propio.
					heatData = {};
					showHeatLegend(text('heat.unavailable'), '');
					return null;
				}
				return checked(response).json().then(paintHeatmap);
			});
	}

	function paintHeatmap(heatmap) {
		canvasContainer.classList.add('heat-mode');
		heatData = {};
		heatmap.nodes.forEach(function (node) { heatData[node.nodeId] = node; });
		drawHeatLayer(heatmap);
		paintHeatNumbers();
		var top = heatmap.nodes.reduce(function (best, node) { return node.executions > best.executions ? node : best; },
				{ executions: 0, name: '' });
		showHeatLegend(text('heat.title', [heatmap.processId + ' v' + heatmap.version, heatmap.versionLabel]),
				heatmap.instances === 0 ? text('heat.noData')
					: text('heat.summary', [heatmap.instances, heatmap.running, heatmap.completed, top.name, top.executions]));
	}

	/**
	 * Dibuja la capa de calor: cada paso ejecutado es una mancha gris con su intensidad (ejecuciones / máximo),
	 * difuminada con shadowBlur y combinada con «lighten» (gana la más intensa, no se suman); después cada píxel se
	 * colorea con la paleta según su intensidad. El resultado entra en el diagrama como imagen SVG en una capa propia,
	 * bajo las figuras, así que acompaña al zoom y al desplazamiento.
	 */
	function drawHeatLayer(heatmap) {
		removeHeatLayer();
		var shapes = heatmap.nodes
			.filter(function (node) { return node.executions > 0 && elementRegistry.get(node.nodeId); })
			.map(function (node) { return { node: node, shape: elementRegistry.get(node.nodeId) }; });
		if (!shapes.length || heatmap.maxExecutions <= 0) {
			return;
		}
		var pad = HEAT_HALO * 2;
		var box = shapes.reduce(function (acc, item) {
			var s = item.shape;
			return { minX: Math.min(acc.minX, s.x), minY: Math.min(acc.minY, s.y),
				maxX: Math.max(acc.maxX, s.x + s.width), maxY: Math.max(acc.maxY, s.y + s.height) };
		}, { minX: Infinity, minY: Infinity, maxX: -Infinity, maxY: -Infinity });
		var originX = box.minX - pad;
		var originY = box.minY - pad;
		var width = Math.ceil(box.maxX - box.minX + pad * 2);
		var height = Math.ceil(box.maxY - box.minY + pad * 2);
		var surface = document.createElement('canvas');
		surface.width = width;
		surface.height = height;
		var ctx = surface.getContext('2d');
		ctx.fillStyle = '#000';
		ctx.fillRect(0, 0, width, height);
		ctx.globalCompositeOperation = 'lighten';
		// La figura se dibuja fuera del lienzo y solo cae dentro su sombra difuminada (truco de shadowOffset).
		// El difuminado no llega al máximo en el centro de una figura pequeña (un evento): se mide el pico que alcanza
		// cada figura y se escala su gris, para que el mismo número de ejecuciones dé el mismo color en un evento y en una
		// tarea. Todo se dibuja a la escala del pico más bajo y colorize() la deshace.
		shapes.forEach(function (item) { item.peak = peakOf(item.shape); });
		var floor = shapes.reduce(function (min, item) { return Math.min(min, item.peak); }, 1);
		var away = width + height + pad;
		shapes.forEach(function (item) {
			var intensity = Math.max(HEAT_MIN_INTENSITY, item.node.executions / heatmap.maxExecutions);
			var gray = Math.round(intensity * 255 * floor / item.peak);
			ctx.shadowColor = 'rgb(' + gray + ',' + gray + ',' + gray + ')';
			ctx.shadowBlur = HEAT_HALO;
			ctx.shadowOffsetX = away;
			ctx.fillStyle = '#fff';
			traceShape(ctx, item.shape, item.shape.x - originX - away, item.shape.y - originY);
		});
		colorize(ctx, width, height, floor);
		heatImage = document.createElementNS('http://www.w3.org/2000/svg', 'image');
		heatImage.setAttribute('x', originX);
		heatImage.setAttribute('y', originY);
		heatImage.setAttribute('width', width);
		heatImage.setAttribute('height', height);
		heatImage.setAttribute('preserveAspectRatio', 'none');
		heatImage.setAttribute('aria-hidden', 'true');
		heatImage.setAttribute('href', surface.toDataURL('image/png'));
		heatImage.classList.add('bpmncode-heat-image');
		// Índice negativo: la capa queda por debajo de las figuras (diagram-js ordena las capas por índice).
		canvas.getLayer('bpmncode-heat', -1).appendChild(heatImage);
	}

	/** Contorno de la mancha de una figura: rectángulo en tareas; círculo en eventos y decisiones. */
	function traceShape(ctx, shape, x, y) {
		ctx.beginPath();
		if (/Event$/.test(shape.type) || /Gateway$/.test(shape.type)) {
			ctx.arc(x + shape.width / 2, y + shape.height / 2, Math.max(shape.width, shape.height) / 2 + 6, 0, Math.PI * 2);
		}
		else {
			ctx.rect(x - 4, y - 4, shape.width + 8, shape.height + 8);
		}
		ctx.fill();
	}

	/** Intensidad (0..1) que alcanza en su centro la mancha difuminada de una figura dibujada a intensidad máxima. */
	function peakOf(shape) {
		var size = Math.ceil(Math.max(shape.width, shape.height) + HEAT_HALO * 4);
		var probe = document.createElement('canvas');
		probe.width = size;
		probe.height = size;
		var ctx = probe.getContext('2d');
		ctx.fillStyle = '#000';
		ctx.fillRect(0, 0, size, size);
		ctx.shadowColor = '#fff';
		ctx.shadowBlur = HEAT_HALO;
		ctx.shadowOffsetX = size * 2;
		ctx.fillStyle = '#fff';
		traceShape(ctx, shape, (size - shape.width) / 2 - size * 2, (size - shape.height) / 2);
		var center = ctx.getImageData(Math.floor(size / 2), Math.floor(size / 2), 1, 1).data[0];
		return Math.max(center, 1) / 255;
	}

	/**
	 * Convierte la intensidad (canal rojo del gris) en color de la paleta y opacidad.
	 *
	 * @param floor escala a la que se dibujó (el pico más bajo): la intensidad real es la leída dividida por ella
	 */
	function colorize(ctx, width, height, floor) {
		var palette = heatPalette();
		var image = ctx.getImageData(0, 0, width, height);
		var data = image.data;
		for (var i = 0; i < data.length; i += 4) {
			var intensity = Math.min(255, Math.round(data[i] / floor));
			if (intensity < 6) {
				data[i + 3] = 0;
				continue;
			}
			var offset = intensity * 4;
			data[i] = palette[offset];
			data[i + 1] = palette[offset + 1];
			data[i + 2] = palette[offset + 2];
			data[i + 3] = Math.round(Math.min(1, intensity / 255 * 1.6) * HEAT_MAX_OPACITY * 255);
		}
		ctx.putImageData(image, 0, 0);
	}

	/** 256 colores interpolados entre las paradas de la paleta (RGBA consecutivos). */
	function heatPalette() {
		var palette = new Uint8ClampedArray(256 * 4);
		for (var i = 0; i < 256; i++) {
			var t = i / 255;
			var upper = HEAT_GRADIENT.findIndex(function (stop) { return stop[0] >= t; });
			var color;
			if (upper <= 0) {
				color = HEAT_GRADIENT[0][1];
			}
			else {
				var from = HEAT_GRADIENT[upper - 1];
				var to = HEAT_GRADIENT[upper];
				var k = (t - from[0]) / (to[0] - from[0]);
				color = [0, 1, 2].map(function (c) { return from[1][c] + (to[1][c] - from[1][c]) * k; });
			}
			palette[i * 4] = color[0];
			palette[i * 4 + 1] = color[1];
			palette[i * 4 + 2] = color[2];
			palette[i * 4 + 3] = 255;
		}
		return palette;
	}

	function removeHeatLayer() {
		if (heatImage && heatImage.parentNode) {
			heatImage.parentNode.removeChild(heatImage);
		}
		heatImage = null;
	}

	/** Insignias con las cifras de cada paso (solo si se activan con «Mostrar cifras»). */
	function paintHeatNumbers() {
		overlays.remove({ type: 'heat' });
		if (heatNumbersButton.getAttribute('aria-pressed') !== 'true') {
			return;
		}
		Object.keys(heatData).forEach(function (nodeId) {
			var node = heatData[nodeId];
			if (!elementRegistry.get(nodeId)) {
				return;
			}
			if (node.executions > 0) {
				overlays.add(nodeId, 'heat', { position: { top: -11, left: -11 },
					html: create('span', 'path-badge heat-badge', '×' + node.executions) });
			}
			if (node.waiting > 0) {
				overlays.add(nodeId, 'heat', { position: { top: -11, right: 6 },
					html: create('span', 'path-badge path-badge-waiting', text('heat.waiting', [node.waiting])) });
			}
		});
	}

	heatNumbersButton.addEventListener('click', function () {
		var pressed = heatNumbersButton.getAttribute('aria-pressed') === 'true';
		heatNumbersButton.setAttribute('aria-pressed', String(!pressed));
		heatNumbersButton.classList.toggle('active', !pressed);
		paintHeatNumbers();
	});

	/** Ventana emergente con las cifras de un paso, como la de Optimize. */
	function showHeatTip(nodeId) {
		hideHeatTip();
		var node = heatData[nodeId];
		if (!heatMode || !node || !elementRegistry.get(nodeId)) {
			return;
		}
		var tip = create('div', 'heat-tip');
		var title = create('p', 'heat-tip-title');
		title.appendChild(create('strong', null, node.name));
		tip.appendChild(title);
		tip.appendChild(create('p', null, text('heat.tipExecutions', [node.executions, node.instances, node.share])));
		if (node.waiting > 0) {
			tip.appendChild(create('p', null, text('heat.waiting', [node.waiting])));
		}
		heatTipId = overlays.add(nodeId, 'heat-tip', { position: { bottom: -8, left: 0 }, html: tip });
	}

	function hideHeatTip() {
		if (heatTipId !== null) {
			overlays.remove(heatTipId);
			heatTipId = null;
		}
	}

	viewer.get('eventBus').on('element.hover', function (event) {
		var target = event.element.labelTarget || event.element;
		if (heatData[target.id]) {
			showHeatTip(target.id);
		}
	});
	viewer.get('eventBus').on('element.out', function () {
		// Si el elemento está elegido (clic o teclado), su ventana se queda.
		if (selectedId && heatData[selectedId]) {
			showHeatTip(selectedId);
		}
		else {
			hideHeatTip();
		}
	});

	function showHeatLegend(title, summary) {
		document.getElementById('heat-title').textContent = title;
		document.getElementById('heat-summary').textContent = summary;
		heatLegend.hidden = false;
		// La leyenda reduce el lienzo: se vuelve a ajustar el dibujo al espacio que queda.
		canvas.resized();
		fit();
	}

	// --- Arranque -------------------------------------------------------------------------------------------------

	fetch(base + '/i18n/viewer' + langQuery)
		.then(checked)
		.then(function (response) { return response.json(); })
		.then(function (loaded) {
			messages = loaded;
			applyMessages();
			return fetch(api + langQuery);
		})
		.then(checked)
		.then(function (response) { return response.json(); })
		.then(function (views) {
			views.forEach(function (view) {
				select.add(new Option(view.label, view.id));
			});
			if (pathInstance) {
				return loadPath(pathInstance);
			}
			var requested = params.get('view');
			if (requested && views.some(function (view) { return view.id === requested; })) {
				select.value = requested;
			}
			return show(select.value);
		})
		.catch(fail);
})();
